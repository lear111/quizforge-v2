package cloud.treenewbee.quizforge.content.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.catalog.application.PackInfo;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.FileRow;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.RevisionRow;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.content.infrastructure.ImportCatalogMapper;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

/** 接收路径只搬运原始文件；事务中原子预留配额、创建版本与入队，不解压、不执行规则。 */
@Service
public class ImportUploadService {
    public record Accepted(String packId, int revision) {}
    private final CatalogMapper catalog;
    private final ImportCatalogMapper metadata;
    private final ImportQueueMapper jobs;
    private final BlobStore blobs;
    private final QuotaService quotas;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;
    private final long maxBytes;
    private final int dailyLimit;
    private final Path temporaryDirectory;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final cloud.treenewbee.quizforge.admin.application.ExtensionReviewService reviews;

    public ImportUploadService(CatalogMapper catalog, ImportCatalogMapper metadata, ImportQueueMapper jobs,
            BlobStore blobs, QuotaService quotas, TransactionTemplate tx, Clock clock, JsonMapper json,
            @Value("${qf.import.max-upload-bytes:134217728}") long maxBytes,
            @Value("${qf.import.daily-upload-limit:20}") int dailyLimit,
            @Value("${qf.import.temp-dir:${java.io.tmpdir}}") String temporaryDirectory,
            org.springframework.context.ApplicationEventPublisher events,
            cloud.treenewbee.quizforge.admin.application.ExtensionReviewService reviews) {
        if (maxBytes < 1 || maxBytes > ImportService.MAX_UPLOAD_BYTES || dailyLimit < 1) {
            throw new IllegalArgumentException("上传大小与每日次数配置无效");
        }
        this.catalog = catalog;
        this.metadata = metadata;
        this.jobs = jobs;
        this.blobs = blobs;
        this.quotas = quotas;
        this.tx = tx;
        this.clock = clock;
        this.json = json;
        this.maxBytes = maxBytes;
        this.dailyLimit = dailyLimit;
        this.temporaryDirectory = Path.of(temporaryDirectory);
        this.events = events;
        this.reviews = reviews;
    }

    /** 次数按接收尝试计数，防止反复中断绕过每日限制；不占存储配额。 */
    public void preflight(String user, long length, boolean privatePack) {
        if (length > maxBytes) throw tooLarge();
        if (privatePack) {
            var storage = quotas.usage(user).storage();
            if (length >= 0 && length > storage.limitBytes() - storage.usedBytes()) throw quotaExceeded();
        }
        tx.executeWithoutResult(status -> {
            LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai"));
            jobs.ensureDay(user, day);
            if (jobs.upload(user, day, dailyLimit) != 1) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_LIMIT", "请稍后再试");
            }
        });
    }

    public Accepted receivePrivate(String user, String existingPack, String filename, long length, InputStream input) {
        String name = filename(filename);
        if (existingPack != null) requireRejected(user, existingPack);
        preflight(user, length, true);
        Path temporary = null;
        try {
            Files.createDirectories(temporaryDirectory);
            temporary = Files.createTempFile(temporaryDirectory, "qf-original-", ".qbank");
            long count = 0;
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > maxBytes) throw tooLarge();
                    output.write(buffer, 0, read);
                }
            }
            if (length >= 0 && count != length) throw invalid("上传未完整接收，请重试");
            String title = name.substring(0, name.length() - ".qbank".length());
            if (title.length() > 80) title = title.substring(0, 80);
            return acceptFile(user, existingPack, true,
                    new PackInfo(title, "私人题库包", "私人题库包", "其他", List.of(), ""), temporary, name, List.of(), List.of());
        } catch (IOException error) {
            throw new UncheckedIOException("上传未完整接收，请重试", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("清理上传临时文件失败", error); }
            }
        }
    }

    /** 公开上传由原有 multipart 接口调用；接收限制与次数检查在读取 multipart 之前执行。 */
    public Accepted acceptFile(String user, String existingPack, boolean privatePack, PackInfo info, Path archive,
            String rawFilename, List<FileRow> shots, List<String> keep) {
        String name = filename(rawFilename);
        try {
            long size = Files.size(archive);
            if (size > maxBytes) throw tooLarge();
            String hash = blobs.putFile(archive);
            return tx.execute(status -> {
                Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                String pack;
                int revision;
                if (existingPack == null) {
                    pack = Ids.uuidV7();
                    catalog.insertPack(pack, user, now);
                    if (metadata.visibility(pack, user, privatePack ? "private" : "public") != 1) {
                        throw new IllegalStateException("题库包归属已变化");
                    }
                    revision = 1;
                } else {
                    var owned = catalog.lockOwned(existingPack, user).orElseThrow(ImportUploadService::notFound);
                    var before = metadata.owned(existingPack, user, owned.latestRevision()).orElseThrow(ImportUploadService::notFound);
                    if (privatePack != "private".equals(before.visibility())) throw notFound();
                    if (privatePack) requireRejected(user, existingPack);
                    pack = existingPack;
                    revision = owned.latestRevision() + 1;
                    catalog.closePending(pack, "superseded");
                    jobs.cancel(pack, now);
                    if (catalog.advance(pack, revision, now) != 1) throw new IllegalStateException("题库包版本未推进");
                }
                if (privatePack) quotas.setStorage(user, "private_pack", pack + ":" + revision, null, size);
                catalog.insertRevision(new RevisionRow(pack, revision, info.title(), info.summary(), info.description(),
                        info.category(), json.writeValueAsString(info.tags()), info.releaseNotes(), "upload", name,
                        HexFormat.of().parseHex(hash), size, "[]", "pending", null, null, null, null, user, now));
                metadata.validation(pack, revision, "queued", null);
                int position = 0;
                List<FileRow> previous = revision > 1 ? catalog.files(pack, revision - 1) : List.of();
                for (String id : keep) {
                    FileRow shot = previous.stream().filter(file -> file.id().equals(id)).findFirst()
                            .orElseThrow(() -> invalid("要沿用的截图不属于上一个版本"));
                    catalog.insertFile(new FileRow(Ids.uuidV7(), pack, revision, shot.kind(), position++, shot.name(), shot.mime(), shot.size(), shot.blobSha256()));
                }
                for (FileRow shot : shots) {
                    catalog.insertFile(new FileRow(Ids.uuidV7(), pack, revision, "screenshot", position++, shot.name(), shot.mime(), shot.size(), shot.blobSha256()));
                }
                catalog.insertEvent(Ids.uuidV7(), pack, revision, user, revision == 1 ? "published" : "revised",
                        json.writeValueAsString(Map.of("filename", name, "size", size, "sha256", hash)), now);
                jobs.enqueue(Ids.uuidV7(), pack, revision, now);
                Accepted accepted = new Accepted(pack, revision);
                events.publishEvent(accepted);
                return accepted;
            });
        } catch (IOException error) {
            throw new UncheckedIOException("无法保存题库原文件", error);
        }
    }

    public void delete(String user, String pack) {
        tx.executeWithoutResult(status -> {
            catalog.lockOwned(pack, user).orElseThrow(ImportUploadService::notFound);
            if (catalog.delete(pack, user, clock.instant()) != 1) throw notFound();
            jobs.cancel(pack, clock.instant());
            // 尚未开放实例导入；下一切片将按资源与实例的共同引用保留此账目。
            quotas.releasePrefix(user, "private_pack", pack + ":");
            catalog.closePending(pack, "withdrawn");
            catalog.insertEvent(Ids.uuidV7(), pack, null, user, "deleted", "{}", clock.instant());
        });
    }

    public boolean isPublic(String pack, String owner, int revision) {
        return metadata.owned(pack, owner, revision).map(state -> "public".equals(state.visibility())).orElse(false);
    }

    public record ReviewState(String status, String reason) {}

    /** 白名单实时推算：已批准的条目也会在撤销后的下一个请求变为不可用。 */
    public ReviewState reviewState(String user, String pack, int version) {
        var info = metadata.owned(pack, user, version).orElseThrow(ImportUploadService::notFound);
        var revision = catalog.revision(pack, version).orElseThrow(ImportUploadService::notFound);
        if (!"validated".equals(info.validationStatus()) || "rejected".equals(revision.reviewStatus())
                || List.of("withdrawn", "superseded").contains(revision.reviewStatus())) {
            return new ReviewState(revision.reviewStatus(), revision.reviewNote());
        }
        List<String> digests = new java.util.ArrayList<>();
        json.readTree(revision.extensionDigests()).forEach(value -> digests.add(value.asString()));
        var states = reviews.states(digests);
        for (String digest : digests) {
            var state = states.get(digest);
            if (state != null && List.of("rejected", "revoked").contains(state.status())) {
                return new ReviewState("rejected", "revoked".equals(state.status()) ? "拓展已停用"
                        : "拓展未通过审核：" + (state.reason() == null ? "请联系管理员" : state.reason()));
            }
        }
        if (digests.stream().anyMatch(digest -> !states.containsKey(digest) || !"approved".equals(states.get(digest).status()))) {
            return new ReviewState("pending", null);
        }
        return new ReviewState("private".equals(info.visibility()) ? "approved" : revision.reviewStatus(), revision.reviewNote());
    }

    private void requireRejected(String user, String pack) {
        var current = metadata.latestOwned(pack, user).orElseThrow(ImportUploadService::notFound);
        if (!"rejected".equals(reviewState(user, pack, current.revision()).status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PACK_NOT_REJECTED", "只有未通过的私人条目可以重新上传");
        }
    }

    private static String filename(String filename) {
        String name = PackInfo.fileName(filename, "题库.qbank");
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".qbank")) throw invalid("请上传 .qbank 题库包");
        return name;
    }

    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "题库包不存在或已删除"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIBRARY_ARCHIVE", message); }
    private static ApiException tooLarge() { return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "LIBRARY_ARCHIVE_LIMIT", "题库包超过上传大小上限"); }
    private static ApiException quotaExceeded() { return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "STORAGE_QUOTA_EXCEEDED", "存储空间不足"); }
}
