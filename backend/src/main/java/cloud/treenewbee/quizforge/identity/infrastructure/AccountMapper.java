package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 账号的联系方式、密码、角色与封禁。所有写操作都以 user_id 为条件。 */
@Mapper
public interface AccountMapper {

    record PhoneRow(String userId, String phone, boolean verified, Instant verifiedAt) {
    }

    record UnverifiedRow(String channel, String contact) {
    }

    @Select("""
            SELECT u.id, u.email, u.name, u.password_hash, u.password_algo, u.is_admin,
                   u.created_at, u.updated_at, u.version
              FROM account_user u JOIN account_user_phone p ON p.user_id = u.id
             WHERE p.phone = #{phone}
            """)
    Optional<UserRow> findByPhone(@Param("phone") String phone);

    @Select("SELECT user_id, phone, verified, verified_at FROM account_user_phone WHERE user_id = #{userId}")
    Optional<PhoneRow> findPhone(@Param("userId") String userId);

    @Insert("""
            INSERT INTO account_user_phone (user_id, phone, verified, verified_at)
            VALUES (#{userId}, #{phone}, #{verifiedAt} IS NOT NULL, #{verifiedAt})
            """)
    int insertPhone(@Param("userId") String userId, @Param("phone") String phone,
                    @Param("verifiedAt") Instant verifiedAt);

    // 已验证的号码保留原验证时间。
    @Update("""
            UPDATE account_user_phone SET verified = TRUE, verified_at = #{verifiedAt}
             WHERE user_id = #{userId} AND phone = #{phone} AND verified_at IS NULL
            """)
    int markPhoneVerified(@Param("userId") String userId, @Param("phone") String phone,
                          @Param("verifiedAt") Instant verifiedAt);

    // 只能补绑：邮箱仍为空时才写入。
    @Update("""
            UPDATE account_user SET email = #{email}, updated_at = #{now}, version = version + 1
             WHERE id = #{userId} AND email IS NULL
            """)
    int bindEmail(@Param("userId") String userId, @Param("email") String email, @Param("now") Instant now);

    // 带上旧哈希作为条件：并发修改密码时只有一个成功。
    @Update("""
            UPDATE account_user SET password_hash = #{passwordHash}, password_algo = #{passwordAlgo},
                   updated_at = #{now}, version = version + 1
             WHERE id = #{userId} AND password_hash = #{previousHash}
            """)
    int updatePassword(@Param("userId") String userId, @Param("previousHash") String previousHash,
                       @Param("passwordHash") String passwordHash, @Param("passwordAlgo") String passwordAlgo,
                       @Param("now") Instant now);

    @Update("""
            UPDATE account_user SET is_admin = #{admin}, updated_at = #{now}, version = version + 1
             WHERE id = #{userId}
            """)
    int setAdmin(@Param("userId") String userId, @Param("admin") boolean admin, @Param("now") Instant now);

    @Insert("""
            INSERT INTO account_unverified_contact (user_id, channel, contact) VALUES (#{userId}, #{channel}, #{contact})
            ON DUPLICATE KEY UPDATE contact = VALUES(contact)
            """)
    int markUnverified(@Param("userId") String userId, @Param("channel") String channel,
                       @Param("contact") String contact);

    @Delete("DELETE FROM account_unverified_contact WHERE user_id = #{userId} AND channel = #{channel}")
    int clearUnverified(@Param("userId") String userId, @Param("channel") String channel);

    @Select("SELECT channel, contact FROM account_unverified_contact WHERE user_id = #{userId} ORDER BY channel")
    List<UnverifiedRow> listUnverified(@Param("userId") String userId);

    @Select("SELECT EXISTS (SELECT 1 FROM account_block WHERE user_id = #{userId})")
    boolean isBlocked(@Param("userId") String userId);

    @Insert("""
            INSERT INTO account_block (user_id, actor_id, reason, created_at)
            VALUES (#{userId}, #{actorId}, #{reason}, #{now})
            ON DUPLICATE KEY UPDATE actor_id = VALUES(actor_id), reason = VALUES(reason), created_at = VALUES(created_at)
            """)
    int block(@Param("userId") String userId, @Param("actorId") String actorId, @Param("reason") String reason,
              @Param("now") Instant now);

    @Delete("DELETE FROM account_block WHERE user_id = #{userId}")
    int unblock(@Param("userId") String userId);

    @Update("UPDATE auth_device SET revoked_at = #{now} WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeDevices(@Param("userId") String userId, @Param("now") Instant now);

    @Delete("DELETE FROM auth_connect_code WHERE user_id = #{userId}")
    int deleteConnectCodes(@Param("userId") String userId);

    @Select("SELECT EXISTS (SELECT 1 FROM account_user WHERE email IS NOT NULL)")
    boolean anyEmailAccount();

    @Select("SELECT EXISTS (SELECT 1 FROM account_user_phone)")
    boolean anyPhoneAccount();

    /** 按 ID 锁住账号行；调用前账号必须存在。 */
    @Select("SELECT id FROM account_user WHERE id = #{userId} FOR UPDATE")
    String lockUser(@Param("userId") String userId);
}
