package cloud.treenewbee.quizforge.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.ai.infrastructure.AiBalanceMapper;
import cloud.treenewbee.quizforge.ai.infrastructure.AiBalanceMapper.LedgerRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * AI 余额（设计 08 第 1 节）。{@link #change} 是<strong>唯一的余额变动入口</strong>：
 * 锁余额行（SELECT … FOR UPDATE）→ 计算 → 更新余额 → 插入流水，在同一事务内。
 * 使用悲观锁：同一用户的变动排队执行，用最新余额计算；余额可以为负（欠费）。
 *
 * <h2>L5.2 如何调用</h2>
 * <ul>
 *   <li>调用模型前：{@link #hasPositiveBalance(String)}（普通读，不加锁），为 false 时提示充值，不发起调用；</li>
 *   <li>模型返回后：{@link #charge(String, long, String)}，amount 为本次费用（单位 0.0001 元，正数），
 *       ref 为 AI 任务 ID 或请求 ID；同一 ref 重复扣费被唯一约束拒绝（返回 409，余额不变）；</li>
 *   <li>需要退款时用 {@code change(userId, +amount, Kind.REFUND, ref, null, null)}。</li>
 * </ul>
 */
@Service
public class AiBalanceService {

    /** 流水类型。DEFAULT_CREDIT：新老用户的默认额度（每个用户只发放一次，ref = 用户 ID）。 */
    public enum Kind {
        DEFAULT_CREDIT("default_credit"), REDEEM("redeem"), AI_CALL("ai_call"), ADMIN("admin"),
        MIGRATION("migration"), REFUND("refund");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public record Entry(String id, long seq, long delta, long balanceAfter, String kind, String ref, Actor actor,
                        String reason, Instant createdAt) {
    }

    public record Actor(String id, String name) {
    }

    public record LedgerPage(List<Entry> items, Long nextBefore) {
    }

    public record Balance(long balance, boolean overdrawn) {
    }

    /** 管理员单次调整的上限：±100 万元。 */
    static final long MAX_ADJUST = 10_000_000_000L;
    static final int MAX_PAGE = 50;

    private final AiBalanceMapper mapper;
    private final AdminAuditService audit;
    private final Clock clock;
    private final long defaultCredit;

    public AiBalanceService(AiBalanceMapper mapper, AdminAuditService audit, Clock clock,
                            @Value("${qf.ai.default-credit:10000}") long defaultCredit) {
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
        this.defaultCredit = defaultCredit;
    }

    /**
     * 唯一的余额变动入口。必须在事务中调用（加入调用方事务，例如注册）。
     * 同一 (kind, ref) 已入账时抛出 409 且不改变余额。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Entry change(String userId, long delta, Kind kind, String ref, String actorId, String reason) {
        if (ref == null || ref.isBlank() || ref.length() > 100) throw new IllegalArgumentException("Ledger ref is required");
        var account = mapper.lock(userId)
                .orElseThrow(() -> new IllegalStateException("AI account missing for user " + userId));
        if (mapper.ledgerExists(kind.value(), ref)) throw duplicate();
        long balance = Math.addExact(account.balance(), delta);
        long seq = account.ledgerSeq() + 1;
        Instant now = clock.instant();
        if (mapper.update(userId, balance, seq, account.ledgerSeq(), now) != 1) {
            throw new IllegalStateException("AI account changed while locked");
        }
        String id = Ids.uuidV7();
        try {
            mapper.insertLedger(id, userId, seq, delta, balance, kind.value(), ref, actorId, reason, now);
        } catch (DuplicateKeyException error) {
            // 纵深防御：唯一约束拒绝时整个事务回滚，余额不变。
            throw duplicate();
        }
        return new Entry(id, seq, delta, balance, kind.value(), ref, actorId == null ? null : new Actor(actorId, null),
                reason, now);
    }

    /** 注册时（与创建账号同一事务）创建余额行并发放默认额度。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openWithDefaultCredit(String userId) {
        mapper.open(userId, clock.instant());
        change(userId, defaultCredit, Kind.DEFAULT_CREDIT, userId, null, null);
    }

    /** L5.2：模型调用结束后按实际费用扣费；余额可以扣成负数。 */
    @Transactional
    public Entry charge(String userId, long amount, String ref) {
        if (amount <= 0) throw new IllegalArgumentException("Charge amount must be positive");
        return change(userId, -amount, Kind.AI_CALL, ref, null, null);
    }

    /** 检查点恢复在调用方短事务中结算；重复 ref 不抛异常，避免污染调用方事务。 */
    @Transactional
    public Entry chargeOnce(String userId, long amount, String ref) {
        if (amount <= 0) throw new IllegalArgumentException("扣费必须为正数");
        mapper.lock(userId).orElseThrow(() -> new IllegalStateException("AI 余额行不存在"));
        if (mapper.ledgerExists(Kind.AI_CALL.value(), ref)) return null;
        return change(userId, -amount, Kind.AI_CALL, ref, null, null);
    }

    /** L5.2：调用前的普通读检查（不加锁），余额 > 0 才允许发起调用。 */
    public boolean hasPositiveBalance(String userId) {
        return mapper.find(userId).map(account -> account.balance() > 0).orElse(false);
    }

    public Balance balance(String userId) {
        long balance = mapper.find(userId).map(AiBalanceMapper.AccountRow::balance).orElse(0L);
        return new Balance(balance, balance < 0);
    }

    /** 某用户的流水（按 seq 倒序，键集分页）。 */
    public LedgerPage ledger(String userId, Long before, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        var rows = mapper.ledger(userId, before, size + 1);
        var items = rows.stream().limit(size).map(AiBalanceService::entry).toList();
        return new LedgerPage(items, rows.size() > size ? items.getLast().seq() : null);
    }

    /** 管理员增减余额：必须填写原因；写 admin 流水并记入审计。 */
    @Transactional
    public Entry adjust(String actorId, String userId, long delta, String reason) {
        String note = reason == null ? "" : reason.strip();
        int length = note.codePointCount(0, note.length());
        if (delta == 0 || Math.abs(delta) > MAX_ADJUST || length == 0 || length > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        }
        if (mapper.find(userId).isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        Entry entry = change(userId, delta, Kind.ADMIN, Ids.uuidV7(), actorId, note);
        audit.record(actorId, "ai", "ai-balance-adjusted", userId,
                Map.of("delta", delta, "balanceAfter", entry.balanceAfter(), "reason", note, "ledgerId", entry.id()));
        return entry;
    }

    private static Entry entry(LedgerRow row) {
        return new Entry(row.id(), row.seq(), row.delta(), row.balanceAfter(), row.kind(), row.ref(),
                row.actorId() == null ? null : new Actor(row.actorId(), row.actorName()), row.reason(), row.createdAt());
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "LEDGER_DUPLICATE", "该来源已经入账");
    }
}
