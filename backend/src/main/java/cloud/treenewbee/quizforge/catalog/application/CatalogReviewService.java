package cloud.treenewbee.quizforge.catalog.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.admin.application.ExtensionReviewService;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.PackBankRow;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.PackRow;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.RevisionRow;
import cloud.treenewbee.quizforge.notification.application.NotificationService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理员审核题库包版本（需求 FR-10 v1.13、FR-11，设计 04 第 2 节）：每个版本只审核一次；只有版本用到的
 * 全部拓展都已批准时才能通过；通过后更新 approved_revision，审核期间广场继续公开上一个已审核版本。
 * 审核结果以站内通知发给发布者，操作写入 admin_audit 与题库包记录（catalog_pack_event）。
 */
@Service
public class CatalogReviewService {

    public record Pending(String packId, int revision, String title, Owner owner, Instant submittedAt,
                          Integer approvedRevision, long size) {
    }

    public record Owner(String id, String name) {
    }

    /** 与上一个已审核版本的差异（按 bank.json 的 UUID）：added 新增、updated 内容变化、removed 移除、unchanged 未变。 */
    public record BankDiff(String uuid, String title, String change, Integer questionCount, Integer previousQuestionCount) {
    }

    public record Detail(String packId, String status, Owner owner, Integer approvedRevision, int latestRevision,
                         CatalogPublishService.RevisionView revision, String sha256, List<BankDiff> diff,
                         List<Map<String, Object>> extensions, List<String> audioHosts, boolean identicalToApproved,
                         boolean allExtensionsApproved, List<Map<String, Object>> history) {
    }

    private final CatalogMapper mapper;
    private final CatalogPublishService publishing;
    private final ExtensionReviewService extensions;
    private final NotificationService notifications;
    private final AdminAuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final cloud.treenewbee.quizforge.content.infrastructure.ImportCatalogMapper validation;

    public CatalogReviewService(CatalogMapper mapper, CatalogPublishService publishing, ExtensionReviewService extensions,
                                NotificationService notifications, AdminAuditService audit, JsonMapper json, Clock clock,
                                cloud.treenewbee.quizforge.content.infrastructure.ImportCatalogMapper validation) {
        this.mapper = mapper;
        this.publishing = publishing;
        this.extensions = extensions;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.validation = validation;
    }

    public List<Pending> pending() {
        return mapper.pending().stream().map(row -> new Pending(row.packId(), row.revision(), row.title(),
                new Owner(row.ownerId(), row.ownerName()), row.submittedAt(), row.approvedRevision(), row.size())).toList();
    }

    public Detail detail(String packId, int revision) {
        PackRow pack = mapper.pack(packId).orElseThrow(CatalogPublishService::packNotFound);
        RevisionRow row = mapper.revision(packId, revision).orElseThrow(CatalogPublishService::packNotFound);
        List<PackBankRow> banks = mapper.banks(packId, revision);
        Integer approved = pack.approvedRevision();
        List<PackBankRow> previous = approved == null || approved == revision ? List.of() : mapper.banks(packId, approved);
        Map<String, PackBankRow> before = new LinkedHashMap<>();
        previous.forEach(bank -> before.put(bank.bankUuid(), bank));
        List<BankDiff> diff = new ArrayList<>();
        for (PackBankRow bank : banks) {
            PackBankRow old = before.remove(bank.bankUuid());
            String change = approved == null || approved == revision ? "new"
                    : old == null ? "added" : old.bankId().equals(bank.bankId()) ? "unchanged" : "updated";
            diff.add(new BankDiff(bank.bankUuid(), bank.title(), change, bank.questionCount(),
                    old == null ? null : old.questionCount()));
        }
        for (PackBankRow old : before.values()) {
            diff.add(new BankDiff(old.bankUuid(), old.title(), "removed", null, old.questionCount()));
        }
        List<String> digests = digests(row);
        var states = extensions.states(digests);
        List<Map<String, Object>> extensionViews = new ArrayList<>();
        for (String digest : digests) {
            var state = states.get(digest);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("digest", digest);
            view.put("extensionId", state == null ? null : state.extensionId());
            view.put("version", state == null ? null : state.version());
            view.put("name", state == null ? null : state.name());
            view.put("status", state == null ? "pending" : state.status());
            view.put("reason", state == null ? null : state.reason());
            extensionViews.add(view);
        }
        boolean allApproved = extensionViews.stream().allMatch(view -> "approved".equals(view.get("status")));
        boolean identical = false;
        if (approved != null && approved != revision) {
            RevisionRow approvedRow = mapper.revision(packId, approved).orElse(null);
            identical = approvedRow != null && previous.size() == banks.size()
                    && diff.stream().allMatch(item -> "unchanged".equals(item.change()))
                    && digests(approvedRow).equals(digests);
        }
        Set<String> hosts = new TreeSet<>();
        for (PackBankRow bank : banks) hosts.addAll(audioHosts(bank.listening()));
        List<Map<String, Object>> history = mapper.events(packId).stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", event.action());
            item.put("revision", event.revisionOrZero() == 0 ? null : event.revisionOrZero());
            item.put("actor", new Owner(event.actorId(), event.actorName()));
            item.put("detail", json.readValue(event.detail(), Object.class));
            item.put("createdAt", event.createdAt());
            return item;
        }).toList();
        return new Detail(packId, pack.status(), new Owner(pack.ownerId(), ownerName(history, pack.ownerId())), approved,
                pack.latestRevision(), publishing.revisionView(row), HexFormat.of().formatHex(row.blobSha256()), diff,
                extensionViews, List.copyOf(hosts), identical, allApproved, history);
    }

    @Transactional
    public void approve(String actorId, String packId, int revision) {
        PackRow pack = mapper.lock(packId).orElseThrow(CatalogPublishService::packNotFound);
        if (!"active".equals(pack.status())) throw changed("题库包已被发布者删除");
        RevisionRow row = mapper.revision(packId, revision).orElseThrow(CatalogPublishService::packNotFound);
        if (!"validated".equals(validation.validationStatus(packId, revision))) {
            throw changed("题库包尚未通过后台内容校验");
        }
        List<String> digests = digests(row);
        var states = extensions.states(digests);
        List<String> unapproved = digests.stream()
                .filter(digest -> states.get(digest) == null || !"approved".equals(states.get(digest).status())).toList();
        if (!unapproved.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTENSIONS_NOT_APPROVED", "该版本用到的拓展还没有全部批准，请先审核拓展");
        }
        Instant now = now();
        if (mapper.decide(packId, revision, "approved", actorId, null, now) != 1) throw changed("该版本已经审核过或已被新版本代替");
        mapper.approve(packId, revision, now);
        record(actorId, pack, row, "approved", null, now);
        notifications.publishSystem(pack.ownerId(), "题库「" + row.title() + "」第 " + revision + " 版审核通过",
                "你发布的题库包「" + row.title() + "」第 " + revision + " 版已通过审核，现在在题库广场公开。");
    }

    @Transactional
    public void reject(String actorId, String packId, int revision, String reason) {
        String note = reason == null ? "" : reason.strip();
        if (note.isEmpty() || note.codePointCount(0, note.length()) > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请填写拒绝原因（最多 500 字）");
        }
        PackRow pack = mapper.lock(packId).orElseThrow(CatalogPublishService::packNotFound);
        RevisionRow row = mapper.revision(packId, revision).orElseThrow(CatalogPublishService::packNotFound);
        Instant now = now();
        if (mapper.decide(packId, revision, "rejected", actorId, note, now) != 1) throw changed("该版本已经审核过或已被新版本代替");
        record(actorId, pack, row, "rejected", note, now);
        String current = pack.approvedRevision() == null ? "" : "广场继续公开第 " + pack.approvedRevision() + " 版。";
        notifications.publishSystem(pack.ownerId(), "题库「" + row.title() + "」第 " + revision + " 版未通过审核",
                "你发布的题库包「" + row.title() + "」第 " + revision + " 版未通过审核。\n原因：" + note
                        + "\n被拒绝的版本不能修改后重新提交，请修改后上传新版本。" + current);
    }

    private void record(String actorId, PackRow pack, RevisionRow row, String decision, String note, Instant now) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("revision", row.revision());
        detail.put("title", row.title());
        detail.put("sha256", HexFormat.of().formatHex(row.blobSha256()));
        if (note != null) detail.put("reason", note);
        mapper.insertEvent(Ids.uuidV7(), pack.id(), row.revision(), actorId, decision, json.writeValueAsString(detail), now);
        audit.record(actorId, "catalog", "pack-revision-" + decision, pack.id(), detail);
    }

    @SuppressWarnings("unchecked")
    private List<String> digests(RevisionRow row) {
        return (List<String>) json.readValue(row.extensionDigests(), List.class);
    }

    /** 题库引用的外部音频域名（设计 06 第 4.2.1 节：审核页面列出全部外部域名）。 */
    private List<String> audioHosts(String listening) {
        if (listening == null) return List.of();
        List<String> hosts = new ArrayList<>();
        JsonNode root = json.readTree(listening);
        for (JsonNode material : root.path("materials")) {
            String url = material.path("url").asString(null);
            if (url == null) continue;
            try {
                String host = URI.create(url).getHost();
                if (host != null) hosts.add(host);
            } catch (IllegalArgumentException ignored) {
                // 导入时已校验，这里不会出现。
            }
        }
        return hosts;
    }

    private static String ownerName(List<Map<String, Object>> history, String ownerId) {
        return history.stream().map(item -> (Owner) item.get("actor")).filter(owner -> owner.id().equals(ownerId))
                .map(Owner::name).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static ApiException changed(String message) {
        return new ApiException(HttpStatus.CONFLICT, "REVISION_ALREADY_REVIEWED", message);
    }
}
