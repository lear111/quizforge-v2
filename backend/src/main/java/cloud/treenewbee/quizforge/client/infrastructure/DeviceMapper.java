package cloud.treenewbee.quizforge.client.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 设备连接（遗留契约 C-05）。令牌、授权码与授权请求的键在库中只存 SHA-256；所有设备操作都带 user_id 条件。 */
@Mapper
public interface DeviceMapper {

    record Pending(String client, String redirectUri, String state, String challenge, String deviceName) {
    }

    record Grant(String userId, String client, String redirectUri, String challenge, String deviceName, Instant expiresAt) {
    }

    record Device(String id, String userId, String name, String client, Instant createdAt, Instant lastUsedAt, String userName) {
    }

    String DEVICE = "d.id, d.user_id, d.name, d.client, d.created_at, d.last_used_at, u.name AS user_name "
            + "FROM auth_device d JOIN account_user u ON u.id = d.user_id ";

    // ---------------------------------------------------------------- 授权请求

    @Delete("DELETE FROM auth_connect_request WHERE expires_at <= #{now}")
    int deleteExpiredRequests(@Param("now") Instant now);

    @Select("SELECT COUNT(*) FROM auth_connect_request")
    int requestCount();

    @Insert("""
            INSERT INTO auth_connect_request (request_hash, client, redirect_uri, state, challenge, device_name, expires_at)
            VALUES (#{hash}, #{client}, #{redirectUri}, #{state}, #{challenge}, #{deviceName}, #{expiresAt})
            """)
    int insertRequest(@Param("hash") byte[] hash, @Param("client") String client, @Param("redirectUri") String redirectUri,
                      @Param("state") String state, @Param("challenge") String challenge,
                      @Param("deviceName") String deviceName, @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT client, redirect_uri, state, challenge, device_name FROM auth_connect_request
             WHERE request_hash = #{hash} AND expires_at > #{now}
            """)
    Optional<Pending> findRequest(@Param("hash") byte[] hash, @Param("now") Instant now);

    @Delete("DELETE FROM auth_connect_request WHERE request_hash = #{hash}")
    int deleteRequest(@Param("hash") byte[] hash);

    // ---------------------------------------------------------------- 授权码

    @Delete("DELETE FROM auth_connect_code WHERE expires_at <= #{now}")
    int deleteExpiredCodes(@Param("now") Instant now);

    @Insert("""
            INSERT INTO auth_connect_code (code_hash, user_id, client, redirect_uri, challenge, device_name, expires_at)
            VALUES (#{hash}, #{userId}, #{client}, #{redirectUri}, #{challenge}, #{deviceName}, #{expiresAt})
            """)
    int insertCode(@Param("hash") byte[] hash, @Param("userId") String userId, @Param("client") String client,
                   @Param("redirectUri") String redirectUri, @Param("challenge") String challenge,
                   @Param("deviceName") String deviceName, @Param("expiresAt") Instant expiresAt);

    @Select("SELECT user_id, client, redirect_uri, challenge, device_name, expires_at FROM auth_connect_code WHERE code_hash = #{hash}")
    Optional<Grant> findCode(@Param("hash") byte[] hash);

    /** 授权码一次性：只有删除到这一行的请求才能继续（并发兑换只有一个成功）。 */
    @Delete("DELETE FROM auth_connect_code WHERE code_hash = #{hash}")
    int deleteCode(@Param("hash") byte[] hash);

    // ---------------------------------------------------------------- 设备

    @Select("SELECT EXISTS (SELECT 1 FROM account_block WHERE user_id = #{userId})")
    boolean blocked(@Param("userId") String userId);

    @Select("SELECT name FROM account_user WHERE id = #{userId}")
    String userName(@Param("userId") String userId);

    @Insert("""
            INSERT INTO auth_device (id, user_id, token_hash, name, client, created_at, last_used_at)
            VALUES (#{id}, #{userId}, #{tokenHash}, #{name}, #{client}, #{now}, #{now})
            """)
    int insertDevice(@Param("id") String id, @Param("userId") String userId, @Param("tokenHash") byte[] tokenHash,
                     @Param("name") String name, @Param("client") String client, @Param("now") Instant now);

    /** 每个账号最多保留 10 个有效连接，其余（最久未用的）撤销。调用方已锁住账号行。 */
    @Select("""
            SELECT id FROM auth_device WHERE user_id = #{userId} AND revoked_at IS NULL
             ORDER BY last_used_at DESC, created_at DESC, id DESC LIMIT 1000 OFFSET #{keep}
            """)
    List<String> beyondLimit(@Param("userId") String userId, @Param("keep") int keep);

    /** 令牌认证：已撤销、过久未用或账号被封禁都查不到。每次请求都重新检查封禁，封禁立即生效。 */
    @Select("SELECT " + DEVICE + """
            WHERE d.token_hash = #{tokenHash} AND d.revoked_at IS NULL AND d.last_used_at > #{since}
              AND NOT EXISTS (SELECT 1 FROM account_block b WHERE b.user_id = d.user_id)
            """)
    Optional<Device> active(@Param("tokenHash") byte[] tokenHash, @Param("since") Instant since);

    @Update("UPDATE auth_device SET last_used_at = #{now} WHERE id = #{id} AND user_id = #{userId}")
    int touch(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now);

    @Update("UPDATE auth_device SET revoked_at = #{now} WHERE id = #{id} AND user_id = #{userId} AND revoked_at IS NULL")
    int revoke(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now);

    @Update("UPDATE auth_device SET name = #{name} WHERE id = #{id} AND user_id = #{userId} AND revoked_at IS NULL")
    int rename(@Param("id") String id, @Param("userId") String userId, @Param("name") String name);

    @Select("SELECT " + DEVICE + """
            WHERE d.id = #{id} AND d.user_id = #{userId} AND d.revoked_at IS NULL
            """)
    Optional<Device> owned(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT " + DEVICE + """
            WHERE d.user_id = #{userId} AND d.revoked_at IS NULL AND d.last_used_at > #{since}
            ORDER BY d.last_used_at DESC, d.id DESC LIMIT 100
            """)
    List<Device> list(@Param("userId") String userId, @Param("since") Instant since);
}
