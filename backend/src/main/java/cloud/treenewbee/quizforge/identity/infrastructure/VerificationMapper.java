package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 验证码（auth_verification_code）与发送记录（auth_verification_send）。 */
@Mapper
public interface VerificationMapper {

    record CodeRow(String channel, String subjectKey, String nonce, byte[] digest, Instant expiresAt,
                   int attempts, String state, boolean captchaVerified) {
    }

    @Select("""
            SELECT channel, subject_key, nonce, digest, expires_at, attempts, state, captcha_verified
              FROM auth_verification_code WHERE channel = #{channel} AND subject_key = #{subjectKey}
            """)
    Optional<CodeRow> find(@Param("channel") String channel, @Param("subjectKey") String subjectKey);

    // 只在 find 确认行存在后调用，避免对不存在的行加锁读（间隙锁）。
    @Select("""
            SELECT channel, subject_key, nonce, digest, expires_at, attempts, state, captcha_verified
              FROM auth_verification_code WHERE channel = #{channel} AND subject_key = #{subjectKey}
               FOR UPDATE
            """)
    Optional<CodeRow> lock(@Param("channel") String channel, @Param("subjectKey") String subjectKey);

    // 重发替换旧码：同一用途、同一联系方式只保留最新一条，错误次数清零。
    @Insert("""
            INSERT INTO auth_verification_code
                (channel, subject_key, nonce, digest, expires_at, attempts, state, captcha_verified)
            VALUES (#{channel}, #{subjectKey}, #{nonce}, #{digest}, #{expiresAt}, 0, 'pending', #{captchaVerified})
            ON DUPLICATE KEY UPDATE nonce = VALUES(nonce), digest = VALUES(digest), expires_at = VALUES(expires_at),
                attempts = 0, state = 'pending', captcha_verified = VALUES(captcha_verified)
            """)
    int upsert(@Param("channel") String channel, @Param("subjectKey") String subjectKey, @Param("nonce") String nonce,
               @Param("digest") byte[] digest, @Param("expiresAt") Instant expiresAt,
               @Param("captchaVerified") boolean captchaVerified);

    @Update("""
            UPDATE auth_verification_code SET state = #{state}
             WHERE channel = #{channel} AND subject_key = #{subjectKey} AND nonce = #{nonce} AND state = 'pending'
            """)
    int markSent(@Param("channel") String channel, @Param("subjectKey") String subjectKey,
                 @Param("nonce") String nonce, @Param("state") String state);

    @Update("""
            UPDATE auth_verification_code SET attempts = attempts + 1
             WHERE channel = #{channel} AND subject_key = #{subjectKey} AND nonce = #{nonce}
            """)
    int recordFailure(@Param("channel") String channel, @Param("subjectKey") String subjectKey,
                      @Param("nonce") String nonce);

    @Update("""
            UPDATE auth_verification_code SET state = 'consumed'
             WHERE channel = #{channel} AND subject_key = #{subjectKey} AND nonce = #{nonce} AND state = 'sent'
            """)
    int consume(@Param("channel") String channel, @Param("subjectKey") String subjectKey,
                @Param("nonce") String nonce);

    @Delete("DELETE FROM auth_verification_code WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") Instant now);

    /** 按固定顺序锁住通道行，使频率检查与发送记录在并发请求间串行。 */
    @Select("SELECT channel FROM auth_verification_channel WHERE channel = #{channel} FOR UPDATE")
    String lockChannel(@Param("channel") String channel);

    @Select("""
            SELECT COUNT(*) FROM auth_verification_send
             WHERE channel = #{channel} AND address_key = #{addressKey} AND created_at > #{since}
            """)
    long countByAddress(@Param("channel") String channel, @Param("addressKey") String addressKey,
                        @Param("since") Instant since);

    @Select("""
            SELECT COUNT(*) FROM auth_verification_send
             WHERE channel = #{channel} AND ip_key = #{ipKey} AND created_at > #{since}
            """)
    long countByIp(@Param("channel") String channel, @Param("ipKey") String ipKey, @Param("since") Instant since);

    @Select("SELECT COUNT(*) FROM auth_verification_send WHERE channel = #{channel} AND created_at > #{since}")
    long countByChannel(@Param("channel") String channel, @Param("since") Instant since);

    @Insert("""
            INSERT INTO auth_verification_send (id, channel, address_key, ip_key, created_at)
            VALUES (#{id}, #{channel}, #{addressKey}, #{ipKey}, #{createdAt})
            """)
    int insertSend(@Param("id") String id, @Param("channel") String channel, @Param("addressKey") String addressKey,
                   @Param("ipKey") String ipKey, @Param("createdAt") Instant createdAt);

    @Delete("DELETE FROM auth_verification_send WHERE channel = #{channel} AND created_at <= #{before}")
    int deleteSendsBefore(@Param("channel") String channel, @Param("before") Instant before);
}
