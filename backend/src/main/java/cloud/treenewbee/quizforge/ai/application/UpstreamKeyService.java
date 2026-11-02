package cloud.treenewbee.quizforge.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.ai.infrastructure.UpstreamKeyMapper;
import cloud.treenewbee.quizforge.ai.infrastructure.UpstreamKeyMapper.KeyRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 上游 API Key 池（沿用旧平台规则）：最多 20 个，按顺序尝试；Key 加密保存、只写不读，后台只显示末 4 位。
 * 所有修改写入 admin_audit。
 */
@Service
public class UpstreamKeyService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamKeyService.class);
    /** 单价上限：每百万 token 1000 元。 */
    static final long MAX_PRICE = 10_000_000L;

    /** 调用时使用的 Key。toString 不含 Key 本身，避免误写进日志。 */
    public record UpstreamKey(String id, long seq, String apiKey, long inputPrice, long outputPrice) {
        @Override
        public String toString() {
            return "UpstreamKey[" + seq + "/" + id + "]";
        }
    }

    public record Item(String id, long seq, int position, String note, String keyHint, long inputPrice, long outputPrice,
                       boolean enabled, Instant expiresAt, String state, String lastError, Instant lastErrorAt,
                       long calls, long inputTokens, long outputTokens, long spent, Instant createdAt) {
    }

    private final UpstreamKeyMapper mapper;
    private final AiCodeCrypto crypto;
    private final AdminAuditService audit;
    private final Clock clock;

    public UpstreamKeyService(UpstreamKeyMapper mapper, AiCodeCrypto crypto, AdminAuditService audit, Clock clock) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 调用

    /** 本次调用按顺序尝试的 Key（最多 3 个）。 */
    public List<UpstreamKey> candidates() {
        return mapper.usable(clock.instant(), AiGatewaySettings.MAX_KEY_ATTEMPTS).stream().map(this::key).toList();
    }

    /** 指定的 Key（测试连接用，停用或不可用的也可以测试）。 */
    public UpstreamKey key(String id) {
        return key(row(id));
    }

    /** Key 本身被上游拒绝（401/403/欠费等）：标为不可用并告警，等管理员处理。 */
    public void markUnavailable(UpstreamKey key, String reason) {
        mapper.recordError(key.id(), reason, true, clock.instant());
        log.warn("AI Key #{}（{}）被上游拒绝：{}；已标记为不可用，请在后台“AI 服务”中处理", key.seq(), key.id(), reason);
    }

    public void recordError(UpstreamKey key, String reason) {
        mapper.recordError(key.id(), reason, false, clock.instant());
    }

    public void recordUsage(UpstreamKey key, long inputTokens, long outputTokens, long cost) {
        mapper.recordUsage(key.id(), inputTokens, outputTokens, cost);
    }

    public void markOk(UpstreamKey key) {
        mapper.markOk(key.id());
    }

    // ---------------------------------------------------------------- 管理员

    public List<Item> list() {
        return mapper.all().stream().map(UpstreamKeyService::item).toList();
    }

    @Transactional
    public Item add(String actorId, String apiKey, String note, long inputPrice, long outputPrice, Instant expiresAt) {
        String text = note == null ? "" : note.strip();
        Instant now = clock.instant();
        if (apiKey == null || !apiKey.matches("[\\x21-\\x7e]{10,200}") || text.codePointCount(0, text.length()) > 60
                || !price(inputPrice) || !price(outputPrice) || expiresAt != null && !expiresAt.isAfter(now)) {
            throw invalid();
        }
        if (mapper.count() >= AiGatewaySettings.MAX_KEYS) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_KEYS_FULL", "最多保存 " + AiGatewaySettings.MAX_KEYS + " 个 API Key");
        }
        String id = Ids.uuidV7();
        long seq = mapper.maxSeq() + 1;
        try {
            mapper.insert(id, seq, mapper.maxPosition() + 1, text, crypto.encrypt(apiKey, AiCodeCrypto.UPSTREAM_KEY_AAD),
                    apiKey.substring(apiKey.length() - 4), inputPrice, outputPrice, expiresAt, actorId, now);
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_KEYS_CONFLICT", "另一位管理员正在修改 Key，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("seq", seq);
        detail.put("keyHint", apiKey.substring(apiKey.length() - 4));
        detail.put("inputPrice", inputPrice);
        detail.put("outputPrice", outputPrice);
        detail.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        audit.record(actorId, "ai", "ai-key-added", id, detail);
        return item(row(id));
    }

    @Transactional
    public Item setEnabled(String actorId, String id, boolean enabled) {
        KeyRow before = row(id);
        mapper.setEnabled(id, enabled, clock.instant());
        audit.record(actorId, "ai", enabled ? "ai-key-enabled" : "ai-key-disabled", id,
                Map.of("seq", before.seq(), "previousState", before.state()));
        return item(row(id));
    }

    @Transactional
    public Item setPrices(String actorId, String id, long inputPrice, long outputPrice) {
        if (!price(inputPrice) || !price(outputPrice)) throw invalid();
        KeyRow before = row(id);
        mapper.setPrices(id, inputPrice, outputPrice, clock.instant());
        audit.record(actorId, "ai", "ai-key-prices", id, Map.of("seq", before.seq(),
                "inputPrice", List.of(before.inputPrice(), inputPrice), "outputPrice", List.of(before.outputPrice(), outputPrice)));
        return item(row(id));
    }

    /** 调整顺序：必须给出全部 Key 的 ID，各出现一次。 */
    @Transactional
    public List<Item> reorder(String actorId, List<String> ids) {
        List<KeyRow> current = mapper.all();
        if (ids == null || ids.size() != current.size() || new HashSet<>(ids).size() != ids.size()
                || !new HashSet<>(ids).equals(new HashSet<>(current.stream().map(KeyRow::id).toList()))) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_KEYS_CHANGED", "Key 列表已变化，请刷新后重试");
        }
        Instant now = clock.instant();
        for (int i = 0; i < ids.size(); i++) mapper.setPosition(ids.get(i), i + 1, now);
        audit.record(actorId, "ai", "ai-keys-reordered", "ai-keys",
                Map.of("order", ids.stream().map(id -> current.stream().filter(row -> row.id().equals(id)).findFirst()
                        .orElseThrow().seq()).toList()));
        return list();
    }

    @Transactional
    public void delete(String actorId, String id) {
        KeyRow before = row(id);
        mapper.delete(id);
        audit.record(actorId, "ai", "ai-key-deleted", id, Map.of("seq", before.seq(), "keyHint", before.keyHint()));
    }

    @Transactional
    public void auditTest(String actorId, String id, Map<String, Object> result) {
        audit.record(actorId, "ai", "ai-key-tested", id, result);
    }

    private KeyRow row(String id) {
        return mapper.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_KEY_NOT_FOUND", "API Key 不存在"));
    }

    private UpstreamKey key(KeyRow row) {
        return new UpstreamKey(row.id(), row.seq(), crypto.decrypt(row.keyCipher(), AiCodeCrypto.UPSTREAM_KEY_AAD),
                row.inputPrice(), row.outputPrice());
    }

    private static boolean price(long value) {
        return value >= 0 && value <= MAX_PRICE;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
    }

    private static Item item(KeyRow row) {
        return new Item(row.id(), row.seq(), row.position(), row.note(), row.keyHint(), row.inputPrice(), row.outputPrice(),
                row.enabled(), row.expiresAt(), row.state(), row.lastError(), row.lastErrorAt(), row.calls(),
                row.inputTokens(), row.outputTokens(), row.spent(), row.createdAt());
    }
}
