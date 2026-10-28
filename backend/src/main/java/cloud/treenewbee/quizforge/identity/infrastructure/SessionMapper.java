package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SessionMapper {

    @Insert("""
            INSERT INTO auth_session
                (token_hash, user_id, created_at, last_used_at, idle_expires_at, absolute_expires_at, user_agent)
            VALUES (#{tokenHash}, #{userId}, #{createdAt}, #{lastUsedAt}, #{idleExpiresAt},
                    #{absoluteExpiresAt}, #{userAgent})
            """)
    int insert(SessionRow session);

    // At the expiry instant the session is already invalid. A block's existence is sufficient.
    @Select("""
            SELECT s.token_hash, s.user_id, s.created_at, s.last_used_at,
                   s.idle_expires_at, s.absolute_expires_at, s.user_agent
              FROM auth_session s
             WHERE s.token_hash = #{tokenHash}
               AND s.idle_expires_at > #{now} AND s.absolute_expires_at > #{now}
               AND NOT EXISTS (SELECT 1 FROM account_block b WHERE b.user_id = s.user_id)
            """)
    Optional<SessionRow> findActive(@Param("tokenHash") byte[] tokenHash, @Param("now") Instant now);

    @Update("""
            UPDATE auth_session SET last_used_at = #{lastUsedAt}, idle_expires_at = #{idleExpiresAt}
             WHERE token_hash = #{tokenHash}
            """)
    int touch(@Param("tokenHash") byte[] tokenHash, @Param("lastUsedAt") Instant lastUsedAt,
              @Param("idleExpiresAt") Instant idleExpiresAt);

    @Delete("DELETE FROM auth_session WHERE token_hash = #{tokenHash}")
    int deleteByHash(@Param("tokenHash") byte[] tokenHash);

    @Delete("DELETE FROM auth_session WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);

    @Delete("""
            DELETE FROM auth_session WHERE idle_expires_at <= #{now} OR absolute_expires_at <= #{now}
            """)
    int deleteExpired(@Param("now") Instant now);
}
