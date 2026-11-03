package cloud.treenewbee.quizforge.catalog.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.FileRow;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.PackRow;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.RevisionRow;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 发布到题库广场（设计 04 第 2、6.1 节，需求 FR-10 v1.13、FR-11）。
 *
 * <ul>
 *   <li>从本地上传 .qbank：保存作者上传的原文件（以后下载逐字节一致，C-05），进入后台校验队列，
 *       未知拓展登记为待审核。私人题库包也可从主页直接复用原文件发布（设计 04 第 6.0 节）。</li>
 *   <li>发布者只能对整个题库包操作：上传整包新版本、删除整个包。归属写在 SQL 条件里。</li>
 *   <li>每个版本只审核一次。新版本提交时，尚未审核的旧版本不再审核（superseded）；审核期间继续公开上一个已审核版本。</li>
 *   <li>公开题库不计入个人存储配额。</li>
 * </ul>
 */
@Service
public class CatalogPublishService {

    /** 新上传的截图。 */
    public record Screenshot(String name, byte[] bytes) {
    }

    public record Submitted(String packId, int revision) {
    }

    public record RevisionView(int revision, String title, String summary, String description, String category,
                               List<String> tags, String releaseNotes, String source, String filename, long size,
                               String reviewStatus, String reviewNote, Instant reviewedAt, Instant submittedAt,
                               List<BankView> banks, List<FileView> screenshots) {
    }

    public record BankView(String uuid, String title, int questionCount) {
    }

    public record FileView(String id, String name, String mime, long size) {
    }

    public record PackView(String id, String status, int latestRevision, Integer approvedRevision, Instant createdAt,
                           Instant deletedAt, List<RevisionView> revisions) {
    }

    public record Joined(List<String> collections) {
    }

    private final CatalogMapper mapper;
    private final BlobStore blobs;
    private final cloud.treenewbee.quizforge.practice.application.LibraryPracticeService practice;
    private final JsonMapper json;
    private final cloud.treenewbee.quizforge.content.application.ImportUploadService uploads;

    public CatalogPublishService(CatalogMapper mapper, BlobStore blobs,
                                 cloud.treenewbee.quizforge.practice.application.LibraryPracticeService practice, JsonMapper json,
                                 cloud.treenewbee.quizforge.content.application.ImportUploadService uploads) {
        this.mapper = mapper;
        this.blobs = blobs;
        this.practice = practice;
        this.json = json;
        this.uploads = uploads;
    }

    // ---------------------------------------------------------------- 发布

    /** 从本地上传 .qbank。archive 为已写入临时文件的上传内容，调用方负责删除。 */
    public Submitted publishUpload(String userId, PackInfo info, boolean rights, Path archive, String filename,
                                   List<Screenshot> screenshots) {
        requireRights(rights);
        List<FileRow> shots = storeScreenshots(screenshots, 0);
        return submit(userId, null, info, archive, qbankName(filename), shots, List.of());
    }

    /** 上传整包新版本；keepScreenshots 为沿用的上一版本截图 ID。 */
    public Submitted uploadRevision(String userId, String packId, PackInfo info, boolean rights, Path archive, String filename,
                                    List<Screenshot> screenshots, List<String> keepScreenshots) {
        requireRights(rights);
        PackRow pack = mapper.pack(packId).filter(row -> row.ownerId().equals(userId) && "active".equals(row.status()))
                .orElseThrow(CatalogPublishService::packNotFound);
        List<String> keep = keepScreenshots == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(keepScreenshots));
        List<FileRow> shots = storeScreenshots(screenshots, keep.size());
        return submit(userId, pack.id(), info, archive, qbankName(filename), shots, keep);
    }

    private Submitted submit(String userId, String existingPack, PackInfo info, Path archive, String filename,
                             List<FileRow> shots, List<String> keep) {
        var accepted = uploads.acceptFile(userId, existingPack, false, info, archive, filename, shots, keep);
        return new Submitted(accepted.packId(), accepted.revision());
    }

    // ---------------------------------------------------------------- 删除整个包

    @org.springframework.transaction.annotation.Transactional
    public void delete(String userId, String packId) {
        uploads.delete(userId, packId);
    }

    // ---------------------------------------------------------------- 我的发布

    public List<PackView> mine(String userId) {
        return mapper.owned(userId).stream().map(pack -> new PackView(pack.id(), pack.status(), pack.latestRevision(),
                pack.approvedRevision(), pack.createdAt(), pack.deletedAt(),
                mapper.revisions(pack.id()).stream().map(row -> revisionView(row,
                        "active".equals(pack.status()) ? uploads.reviewState(userId, pack.id(), row.revision()) : null))
                        .toList())).toList();
    }

    public RevisionView revisionView(RevisionRow row) {
        return revisionView(row, null);
    }

    private RevisionView revisionView(RevisionRow row,
            cloud.treenewbee.quizforge.content.application.ImportUploadService.ReviewState state) {
        return new RevisionView(row.revision(), row.title(), row.summary(), row.description(), row.category(),
                readTags(row.tags()), row.releaseNotes(), row.source(), row.filename(), row.size(),
                state == null ? row.reviewStatus() : state.status(), state == null ? row.reviewNote() : state.reason(),
                row.reviewedAt(), row.submittedAt(),
                mapper.banks(row.packId(), row.revision()).stream()
                        .map(bank -> new BankView(bank.bankUuid(), bank.title(), bank.questionCount())).toList(),
                mapper.files(row.packId(), row.revision()).stream()
                        .map(file -> new FileView(file.id(), file.name(), file.mime(), file.size())).toList());
    }

    /** 截图读取权限：所有者、管理员，或当前公开（已审核且未删除）的版本。 */
    public FileRow file(String userId, boolean admin, String fileId) {
        FileRow file = mapper.file(fileId).orElseThrow(CatalogPublishService::fileNotFound);
        PackRow pack = mapper.pack(file.packId()).orElseThrow(CatalogPublishService::fileNotFound);
        boolean visible = admin || pack.ownerId().equals(userId)
                || "active".equals(pack.status()) && pack.approvedRevision() != null && pack.approvedRevision() == file.revision()
                    && uploads.isPublic(pack.id(), pack.ownerId(), file.revision());
        if (!visible) throw fileNotFound();
        return file;
    }

    // ---------------------------------------------------------------- 导入练习空间

    /** 每次加入都创建独立实例；广场来源可由后续版本更新流程识别。 */
    public Joined join(String userId, String packId, List<String> bankUuids) {
        return new Joined(practice.create(userId, packId, null, bankUuids, null, false).stream()
                .map(cloud.treenewbee.quizforge.practice.application.LibraryPracticeService.Created::id).toList());
    }

    // ---------------------------------------------------------------- 内部

    private List<FileRow> storeScreenshots(List<Screenshot> screenshots, int kept) {
        List<Screenshot> items = screenshots == null ? List.of() : screenshots;
        if (items.size() + kept > PackInfo.MAX_SCREENSHOTS) throw PackInfo.invalid("截图最多 " + PackInfo.MAX_SCREENSHOTS + " 张");
        List<FileRow> stored = new ArrayList<>();
        for (Screenshot shot : items) {
            if (shot.bytes().length == 0 || shot.bytes().length > PackInfo.MAX_SCREENSHOT_BYTES) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "PACK_INFO_INVALID", "每张截图不能超过 3 MiB");
            }
            String mime = PackInfo.imageMime(shot.bytes());
            if (mime == null) throw PackInfo.invalid("截图只接受 PNG、JPEG、WebP 图片");
            try {
                String sha = blobs.put(shot.bytes());
                stored.add(new FileRow(null, null, 0, "screenshot", 0, PackInfo.fileName(shot.name(), "截图"), mime,
                        shot.bytes().length, HexFormat.of().parseHex(sha)));
            } catch (IOException error) {
                throw new UncheckedIOException("无法保存截图", error);
            }
        }
        return stored;
    }

    @SuppressWarnings("unchecked")
    private List<String> readTags(String tags) {
        return tags == null ? List.of() : (List<String>) json.readValue(tags, List.class);
    }

    private static String qbankName(String raw) {
        String name = PackInfo.fileName(raw, "题库.qbank");
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".qbank")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIBRARY_ARCHIVE", "请上传 .qbank 题库包");
        }
        return name;
    }

    private static void requireRights(boolean rights) {
        if (!rights) throw PackInfo.invalid("请确认你有权分享这些题库");
    }

    static ApiException packNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "题库包不存在或已删除");
    }

    private static ApiException fileNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在");
    }
}
