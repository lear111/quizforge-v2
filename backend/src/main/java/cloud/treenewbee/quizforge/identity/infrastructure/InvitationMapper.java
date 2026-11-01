package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 邀请码（account_invitation）。 */
@Mapper
public interface InvitationMapper {

    record InvitationRow(String id, String prefix, String createdBy, Instant createdAt, Instant expiresAt,
                         Instant revokedAt, Instant usedAt, String userId, String usedName, String usedEmail,
                         String usedPhone) {
    }

    @Insert("""
            INSERT INTO account_invitation (id, digest, prefix, created_by, created_at, expires_at)
            VALUES (#{id}, #{digest}, #{prefix}, #{createdBy}, #{createdAt}, #{expiresAt})
            """)
    int insert(@Param("id") String id, @Param("digest") byte[] digest, @Param("prefix") String prefix,
               @Param("createdBy") String createdBy, @Param("createdAt") Instant createdAt,
               @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT id FROM account_invitation
             WHERE digest = #{digest} AND used_at IS NULL AND revoked_at IS NULL AND expires_at > #{now}
            """)
    Optional<String> findUsable(@Param("digest") byte[] digest, @Param("now") Instant now);

    // 条件更新保证一个邀请码只能被一个账号使用；并发注册时只有一个影响 1 行。
    @Update("""
            UPDATE account_invitation SET used_at = #{now}, user_id = #{userId}
             WHERE id = #{id} AND used_at IS NULL AND revoked_at IS NULL AND expires_at > #{now}
            """)
    int consume(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now);

    @Update("""
            UPDATE account_invitation SET revoked_at = #{now}
             WHERE id = #{id} AND used_at IS NULL AND revoked_at IS NULL
            """)
    int revoke(@Param("id") String id, @Param("now") Instant now);

    @Select("""
            SELECT i.id, i.prefix, i.created_by, i.created_at, i.expires_at, i.revoked_at, i.used_at, i.user_id,
                   u.name AS used_name, u.email AS used_email, p.phone AS used_phone
              FROM account_invitation i
              LEFT JOIN account_user u ON u.id = i.user_id
              LEFT JOIN account_user_phone p ON p.user_id = i.user_id
             WHERE i.id = #{id}
            """)
    Optional<InvitationRow> find(@Param("id") String id);

    @Select("""
            SELECT i.id, i.prefix, i.created_by, i.created_at, i.expires_at, i.revoked_at, i.used_at, i.user_id,
                   u.name AS used_name, u.email AS used_email, p.phone AS used_phone
              FROM account_invitation i
              LEFT JOIN account_user u ON u.id = i.user_id
              LEFT JOIN account_user_phone p ON p.user_id = i.user_id
             ORDER BY i.created_at DESC, i.id
             LIMIT #{limit}
            """)
    List<InvitationRow> list(@Param("limit") int limit);
}
