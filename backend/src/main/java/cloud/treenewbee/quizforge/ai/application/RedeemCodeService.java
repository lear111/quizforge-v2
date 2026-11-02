package cloud.treenewbee.quizforge.ai.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.ai.infrastructure.RedeemCodeMapper;
import cloud.treenewbee.quizforge.ai.infrastructure.RedeemCodeMapper.CodeRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 兑换码（沿用旧平台 AiPoints 的规则，设计 08 第 0 节）：
 * <ul>
 *   <li>格式 AI-XXXX-XXXX-XXXX-XXXX（16 位，字母表去掉易混的 I、L、O、U）；库中只存规范化码体的 SHA-256 与前缀，
 *       完整码用 AES-256-GCM 加密保存；完整码只在生成时返回一次；</li>
 *   <li>输入时忽略大小写、空白与连字符，O→0、I/L→1；</li>
 *   <li>每个码只能兑换一次；同一用户 1 小时内输错 10 次锁定（单实例内存计数，与旧版一致）；</li>
 *   <li>兑换写 redeem 流水，ref 为兑换码记录 ID，与标记已兑换在同一事务内。</li>
 * </ul>
 */
@Service
public class RedeemCodeService {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final Pattern BODY = Pattern.compile("[0-9A-HJKMNP-TV-Z]{16}");
    static final int MAX_FAILURES = 10;
    static final Duration LOCK_WINDOW = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String id, long seq, String code, long points, Instant expiresAt) {
    }

    public record Item(String id, long seq, String prefix, long points, String note, String state, Instant createdAt,
                       Instant expiresAt, Instant revokedAt, Instant redeemedAt, Redeemer redeemedBy) {
    }

    public record Redeemer(String id, String name) {
    }

    public record Page(List<Item> items, Long nextBefore) {
    }

    public record Redeemed(long added, long balance) {
    }

    private record Failures(int count, Instant windowEnd) {
    }

    private final RedeemCodeMapper mapper;
    private final AiBalanceService balances;
    private final AiCodeCrypto crypto;
    private final AdminAuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public RedeemCodeService(RedeemCodeMapper mapper, AiBalanceService balances, AiCodeCrypto crypto,
                             AdminAuditService audit, Clock clock, TransactionTemplate transactions) {
        this.mapper = mapper;
        this.balances = balances;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    // ---------------------------------------------------------------- 管理员

    /** 批量生成：1–200 个，每个 0.01–10000 元，有效期 1–3650 天，备注最多 60 字。 */
    @Transactional
    public List<Issued> create(String actorId, int count, long points, int days, String note) {
        String text = note == null ? "" : note.strip();
        if (count < 1 || count > 200 || points < 100 || points > 100_000_000L || days < 1 || days > 3650
                || text.codePointCount(0, text.length()) > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        }
        Instant now = clock.instant();
        Instant expires = now.plus(Duration.ofDays(days));
        long seq = mapper.maxSeq();
        List<Issued> issued = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                StringBuilder body = new StringBuilder();
                byte[] entropy = new byte[16];
                RANDOM.nextBytes(entropy);
                for (byte value : entropy) body.append(ALPHABET.charAt(value & 31));
                String code = "AI-" + String.join("-", body.substring(0, 4), body.substring(4, 8),
                        body.substring(8, 12), body.substring(12));
                String id = Ids.uuidV7();
                mapper.insert(id, ++seq, digest(body.toString()), code.substring(0, 7), points, text, actorId, now,
                        expires, crypto.encrypt(code));
                issued.add(new Issued(id, seq, code, points, expires));
            }
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "CODES_CONFLICT", "另一位管理员正在生成兑换码，请重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("count", count);
        detail.put("points", points);
        detail.put("days", days);
        detail.put("note", text);
        detail.put("firstSeq", issued.getFirst().seq());
        audit.record(actorId, "ai", "ai-codes-created", "ai-codes", detail);
        return issued;
    }

    public Page page(Long before, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        Instant now = clock.instant();
        var rows = mapper.page(before, size + 1);
        var items = rows.stream().limit(size).map(row -> item(row, now)).toList();
        return new Page(items, rows.size() > size ? items.getLast().seq() : null);
    }

    @Transactional
    public Item revoke(String actorId, String id) {
        CodeRow row = mapper.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_CODE_NOT_FOUND", "兑换码不存在"));
        if (row.redeemedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_CODE_REDEEMED", "兑换码已被使用，不能停用");
        }
        if (row.revokedAt() == null && mapper.revoke(id, clock.instant()) == 1) {
            audit.record(actorId, "ai", "ai-code-revoked", id, Map.of("seq", row.seq(), "prefix", row.prefix()));
        }
        return item(mapper.find(id).orElseThrow(), clock.instant());
    }

    // ---------------------------------------------------------------- 用户

    /** 网页兑换。无效、已使用、已停用、已过期统一提示；1 小时内输错 10 次锁定。 */
    public Redeemed redeem(String userId, String raw) {
        Instant now = clock.instant();
        Failures prior = failures.get(userId);
        if (prior != null && prior.windowEnd().isAfter(now) && prior.count() >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "REDEEM_LOCKED", "兑换码输错次数太多，请 1 小时后再试");
        }
        String body = normalize(raw);
        // 标记已兑换与入账在同一事务内；失败（无效、已使用、并发中输给别人）时返回 null。
        Redeemed result = body == null ? null : transactions.execute(status -> redeemBody(userId, body));
        if (result == null) {
            failures.compute(userId, (key, current) -> current == null || !current.windowEnd().isAfter(now)
                    ? new Failures(1, now.plus(LOCK_WINDOW)) : new Failures(current.count() + 1, current.windowEnd()));
            if (failures.size() > 10_000) failures.values().removeIf(entry -> !entry.windowEnd().isAfter(now));
            throw new ApiException(HttpStatus.BAD_REQUEST, "REDEEM_CODE_INVALID", "兑换码无效、已使用或已过期");
        }
        failures.remove(userId);
        return result;
    }

    private Redeemed redeemBody(String userId, String body) {
        Instant now = clock.instant();
        CodeRow row = mapper.findByDigest(digest(body)).orElse(null);
        if (row == null || mapper.redeem(row.id(), userId, now) != 1) return null;
        var entry = balances.change(userId, row.points(), AiBalanceService.Kind.REDEEM, row.id(), null, null);
        return new Redeemed(row.points(), entry.balanceAfter());
    }

    /** 与旧平台相同的规范化；不符合格式返回 null。 */
    static String normalize(String raw) {
        if (raw == null || raw.length() > 64) return null;
        String value = raw.toUpperCase(Locale.ROOT).replaceAll("[\\s\\p{Z}\\uFEFF-]", "").replaceFirst("^AI", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        return BODY.matcher(value).matches() ? value : null;
    }

    static byte[] digest(String body) {
        return AiCodeCrypto.sha256(body.toUpperCase(Locale.ROOT));
    }

    private static Item item(CodeRow row, Instant now) {
        String state = row.redeemedAt() != null ? "redeemed" : row.revokedAt() != null ? "revoked"
                : !row.expiresAt().isAfter(now) ? "expired" : "available";
        return new Item(row.id(), row.seq(), row.prefix(), row.points(), row.note(), state, row.createdAt(),
                row.expiresAt(), row.revokedAt(), row.redeemedAt(),
                row.redeemedBy() == null ? null : new Redeemer(row.redeemedBy(), row.redeemedByName()));
    }
}
