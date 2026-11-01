package cloud.treenewbee.quizforge.practice.resources;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserResourceMapper {
    @Insert("""
            INSERT INTO practice_user_resource (user_id, blob_sha256, mime, size, created_at)
            VALUES (#{userId}, #{blobSha256}, #{mime}, #{size}, #{createdAt})
            ON DUPLICATE KEY UPDATE blob_sha256 = practice_user_resource.blob_sha256
            """)
    int insertIfAbsent(UserResourceRow row);

    @Select("""
            SELECT user_id, blob_sha256, mime, size, created_at FROM practice_user_resource
            WHERE user_id = #{userId} AND blob_sha256 = #{sha256}
            """)
    Optional<UserResourceRow> find(@Param("userId") String userId, @Param("sha256") byte[] sha256);
}
