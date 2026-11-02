package cloud.treenewbee.quizforge.ai.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 兑换码（ai_redeem_code）。 */
@Mapper
public interface RedeemCodeMapper {

    record CodeRow(String id, long seq, String prefix, long points, String note, String createdBy,
                   Instant createdAt, Instant expiresAt, Instant revokedAt, Instant redeemedAt, String redeemedBy,
                   String redeemedByName) {
    }

    // 不加锁：两位管理员同时生成时由 seq 唯一约束拒绝其中一批（整批回滚，提示重试）。
    @Select("SELECT COALESCE(MAX(seq), 0) FROM ai_redeem_code")
    long maxSeq();

    @Insert("""
            INSERT INTO ai_redeem_code (id, seq, digest, prefix, points, note, created_by, created_at, expires_at, code_cipher)
            VALUES (#{id}, #{seq}, #{digest}, #{prefix}, #{points}, #{note}, #{createdBy}, #{createdAt}, #{expiresAt}, #{cipher})
            """)
    int insert(@Param("id") String id, @Param("seq") long seq, @Param("digest") byte[] digest,
               @Param("prefix") String prefix, @Param("points") long points, @Param("note") String note,
               @Param("createdBy") String createdBy, @Param("createdAt") Instant createdAt,
               @Param("expiresAt") Instant expiresAt, @Param("cipher") String cipher);

    @Select("""
            SELECT c.id, c.seq, c.prefix, c.points, c.note, c.created_by, c.created_at, c.expires_at, c.revoked_at,
                   c.redeemed_at, c.redeemed_by, u.name AS redeemed_by_name
              FROM ai_redeem_code c LEFT JOIN account_user u ON u.id = c.redeemed_by
             WHERE c.digest = #{digest}
            """)
    Optional<CodeRow> findByDigest(@Param("digest") byte[] digest);

    @Select("""
            SELECT c.id, c.seq, c.prefix, c.points, c.note, c.created_by, c.created_at, c.expires_at, c.revoked_at,
                   c.redeemed_at, c.redeemed_by, u.name AS redeemed_by_name
              FROM ai_redeem_code c LEFT JOIN account_user u ON u.id = c.redeemed_by
             WHERE c.id = #{id}
            """)
    Optional<CodeRow> find(@Param("id") String id);

    /** 条件更新保证每个码只能兑换一次：并发兑换同一个码时只有一个影响 1 行。 */
    @Update("""
            UPDATE ai_redeem_code SET redeemed_at = #{now}, redeemed_by = #{userId}
             WHERE id = #{id} AND redeemed_at IS NULL AND revoked_at IS NULL AND expires_at > #{now}
            """)
    int redeem(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now);

    @Update("UPDATE ai_redeem_code SET revoked_at = #{now} WHERE id = #{id} AND redeemed_at IS NULL AND revoked_at IS NULL")
    int revoke(@Param("id") String id, @Param("now") Instant now);

    @Select("""
            <script>
            SELECT c.id, c.seq, c.prefix, c.points, c.note, c.created_by, c.created_at, c.expires_at, c.revoked_at,
                   c.redeemed_at, c.redeemed_by, u.name AS redeemed_by_name
              FROM ai_redeem_code c LEFT JOIN account_user u ON u.id = c.redeemed_by
             <if test="beforeSeq != null">WHERE c.seq &lt; #{beforeSeq}</if>
             ORDER BY c.seq DESC
             LIMIT #{limit}
            </script>
            """)
    List<CodeRow> page(@Param("beforeSeq") Long beforeSeq, @Param("limit") int limit);
}
