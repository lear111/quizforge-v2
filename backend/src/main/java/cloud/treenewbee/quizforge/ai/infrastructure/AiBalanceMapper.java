package cloud.treenewbee.quizforge.ai.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 余额（ai_account）与流水（ai_ledger）。写方法只供 AiBalanceService 的唯一变动入口使用；
 * 其他代码不得直接更新 ai_account。
 */
@Mapper
public interface AiBalanceMapper {

    record AccountRow(String userId, long balance, long ledgerSeq) {
    }

    record LedgerRow(String id, String userId, long seq, long delta, long balanceAfter, String kind, String ref,
                     String actorId, String actorName, String reason, Instant createdAt) {
    }

    record Mismatch(String userId, long balance, long ledgerSum) {
    }

    record Gap(String userId, long seq, long balanceAfter, long delta, Long previousSeq, Long previousBalance) {
    }

    @Insert("""
            INSERT INTO ai_account (user_id, balance, ledger_seq, updated_at) VALUES (#{userId}, 0, 0, #{now})
            """)
    int open(@Param("userId") String userId, @Param("now") Instant now);

    @Select("SELECT user_id, balance, ledger_seq FROM ai_account WHERE user_id = #{userId}")
    Optional<AccountRow> find(@Param("userId") String userId);

    /** 只在注册时创建的、确定存在的行上加锁（避免对不存在的行加锁读产生间隙锁）。 */
    @Select("SELECT user_id, balance, ledger_seq FROM ai_account WHERE user_id = #{userId} FOR UPDATE")
    Optional<AccountRow> lock(@Param("userId") String userId);

    @Select("SELECT EXISTS (SELECT 1 FROM ai_ledger WHERE kind = #{kind} AND ref = #{ref})")
    boolean ledgerExists(@Param("kind") String kind, @Param("ref") String ref);

    @Update("""
            UPDATE ai_account SET balance = #{balance}, ledger_seq = #{seq}, updated_at = #{now}
             WHERE user_id = #{userId} AND ledger_seq = #{previousSeq}
            """)
    int update(@Param("userId") String userId, @Param("balance") long balance, @Param("seq") long seq,
               @Param("previousSeq") long previousSeq, @Param("now") Instant now);

    @Insert("""
            INSERT INTO ai_ledger (id, user_id, seq, delta, balance_after, kind, ref, actor_id, reason, created_at)
            VALUES (#{id}, #{userId}, #{seq}, #{delta}, #{balanceAfter}, #{kind}, #{ref}, #{actorId}, #{reason}, #{now})
            """)
    int insertLedger(@Param("id") String id, @Param("userId") String userId, @Param("seq") long seq,
                     @Param("delta") long delta, @Param("balanceAfter") long balanceAfter, @Param("kind") String kind,
                     @Param("ref") String ref, @Param("actorId") String actorId, @Param("reason") String reason,
                     @Param("now") Instant now);

    /** 某用户的流水，按 seq 倒序键集分页；用户 ID 只来自会话（或管理员查看的目标）。 */
    @Select("""
            <script>
            SELECT l.id, l.user_id, l.seq, l.delta, l.balance_after, l.kind, l.ref, l.actor_id, u.name AS actor_name,
                   l.reason, l.created_at
              FROM ai_ledger l LEFT JOIN account_user u ON u.id = l.actor_id
             WHERE l.user_id = #{userId}
               <if test="beforeSeq != null">AND l.seq &lt; #{beforeSeq}</if>
             ORDER BY l.seq DESC
             LIMIT #{limit}
            </script>
            """)
    List<LedgerRow> ledger(@Param("userId") String userId, @Param("beforeSeq") Long beforeSeq,
                           @Param("limit") int limit);

    // ---------------------------------------------------------------- 对账

    @Select("""
            SELECT a.user_id, a.balance, COALESCE(SUM(l.delta), 0) AS ledger_sum
              FROM ai_account a LEFT JOIN ai_ledger l ON l.user_id = a.user_id
             GROUP BY a.user_id, a.balance
            HAVING a.balance <> COALESCE(SUM(l.delta), 0)
            """)
    List<Mismatch> balanceMismatches();

    /** balance_after 不连续：第一笔应为 seq 1 且 balance_after = delta；之后每笔 = 上一笔 + delta，seq 连续。 */
    @Select("""
            SELECT user_id, seq, balance_after, delta, previous_seq, previous_balance FROM (
              SELECT user_id, seq, balance_after, delta,
                     LAG(seq) OVER (PARTITION BY user_id ORDER BY seq) AS previous_seq,
                     LAG(balance_after) OVER (PARTITION BY user_id ORDER BY seq) AS previous_balance
                FROM ai_ledger) t
             WHERE (previous_seq IS NULL AND (seq <> 1 OR balance_after <> delta))
                OR (previous_seq IS NOT NULL AND (seq <> previous_seq + 1 OR balance_after <> previous_balance + delta))
            """)
    List<Gap> ledgerGaps();
}
