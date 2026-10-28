package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BlobMapper {
    @Insert("""
            INSERT IGNORE INTO content_blob (sha256, size, mime, created_at)
            VALUES (#{sha256}, #{size}, #{mime}, #{createdAt})
            """)
    int insertIfAbsent(BlobRow row);

    @Select("""
            SELECT sha256, size, mime, created_at
              FROM content_blob WHERE sha256 = #{sha256}
            """)
    Optional<BlobRow> findBySha256(@Param("sha256") byte[] sha256);
}
