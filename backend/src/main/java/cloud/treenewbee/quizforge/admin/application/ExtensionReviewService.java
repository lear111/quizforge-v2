package cloud.treenewbee.quizforge.admin.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.infrastructure.ExtensionReviewMapper;
import cloud.treenewbee.quizforge.admin.infrastructure.ExtensionReviewMapper.ListRow;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 拓展审核白名单（C-04，设计 04 第 6.2 节）。导入（私人导入与发布）时为未知的拓展摘要自动登记 pending；
 * 管理员批准、拒绝（待审核时）或撤销（已批准时）。审核按摘要记录，一次决定对所有用到它的题库生效；
 * 题库可用性在读取时由白名单推算，这里不批量修改任何题库。
 */
@Service
public class ExtensionReviewService {

    public record Item(String digest, String extensionId, String version, String name, String status, Reviewer reviewer,
                       Instant reviewedAt, String reason, Instant createdAt) {
    }

    public record Reviewer(String id, String name) {
    }

    public record Impact(long privateCollections, List<ExtensionReviewMapper.AffectedPack> packs) {
    }

    private static final Set<String> STATUSES = Set.of("pending", "approved", "rejected", "revoked");

    private final ExtensionReviewMapper mapper;
    private final AdminAuditService audit;
    private final Clock clock;

    public ExtensionReviewService(ExtensionReviewMapper mapper, AdminAuditService audit, Clock clock) {
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    /** 导入时调用（在导入事务内）：未知摘要登记为待审核，已有记录不变。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerPending(String digestHex, String extensionId, String version) {
        mapper.registerPending(HexFormat.of().parseHex(digestHex), extensionId, version, now());
    }

    public List<Item> list(String status) {
        if (status != null && !STATUSES.contains(status)) throw invalid();
        return mapper.list(status, null, 200).stream().map(ExtensionReviewService::item).toList();
    }

    /** 指定摘要的审核记录（十六进制摘要 → 记录；没有记录的不返回）。 */
    public Map<String, Item> states(List<String> digests) {
        Map<String, Item> result = new LinkedHashMap<>();
        if (digests.isEmpty()) return result;
        for (ListRow row : mapper.list(null, digests.stream().map(ExtensionReviewService::digest).toList(), digests.size())) {
            Item item = item(row);
            result.put(item.digest(), item);
        }
        return result;
    }

    /** 同一拓展 ID 的其他已登记版本，供管理员对照。 */
    public List<Item> siblings(String digestHex) {
        byte[] digest = digest(digestHex);
        var row = mapper.findByDigest(digest).orElseThrow(ExtensionReviewService::notFound);
        return mapper.siblings(row.extensionId(), digest).stream().map(ExtensionReviewService::item).toList();
    }

    public Impact impact(String digestHex) {
        byte[] digest = digest(digestHex);
        mapper.findByDigest(digest).orElseThrow(ExtensionReviewService::notFound);
        return new Impact(mapper.privateCollections(digest), mapper.packs(digestHex.toLowerCase(Locale.ROOT)));
    }

    @Transactional
    public Item approve(String actorId, String digestHex) {
        return decide(actorId, digestHex, List.of("pending", "rejected", "revoked"), "approved", null, "extension-approved");
    }

    /** 拒绝待审核的拓展，或撤销已批准的拓展；都必须填写原因。 */
    @Transactional
    public Item revoke(String actorId, String digestHex, String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 500) throw invalid();
        var current = mapper.findByDigest(digest(digestHex)).orElseThrow(ExtensionReviewService::notFound);
        return "approved".equals(current.status())
                ? decide(actorId, digestHex, List.of("approved"), "revoked", text, "extension-revoked")
                : decide(actorId, digestHex, List.of("pending"), "rejected", text, "extension-rejected");
    }

    private Item decide(String actorId, String digestHex, List<String> from, String status, String reason, String action) {
        byte[] digest = digest(digestHex);
        var before = mapper.findByDigest(digest).orElseThrow(ExtensionReviewService::notFound);
        if (mapper.decide(digest, from, status, actorId, reason, now()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTENSION_REVIEW_CHANGED", "拓展的审核状态已变化，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("extensionId", before.extensionId());
        detail.put("version", before.version());
        detail.put("from", before.status());
        if (reason != null) detail.put("reason", reason);
        audit.record(actorId, "admin", action, digestHex.toLowerCase(Locale.ROOT), detail);
        return item(mapper.list(null, List.of(digest), 1).getFirst());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static byte[] digest(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) throw notFound();
        return HexFormat.of().parseHex(hex);
    }

    private static Item item(ListRow row) {
        return new Item(HexFormat.of().formatHex(row.digest()), row.extensionId(), row.version(), row.name(), row.status(),
                row.reviewer() == null ? null : new Reviewer(row.reviewer(), row.reviewerName()), row.reviewedAt(),
                row.reason(), row.createdAt());
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EXTENSION_NOT_FOUND", "拓展不存在");
    }
}
