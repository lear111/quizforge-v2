package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExtensionFileMapper {
    @Insert("""
            INSERT IGNORE INTO content_extension_file (package_digest, path, blob_sha256)
            VALUES (#{packageDigest}, #{path}, #{blobSha256})
            """)
    int insertIfAbsent(ExtensionFileRow row);

    @Insert("""
            INSERT INTO content_extension_file (package_digest, path, blob_sha256)
            VALUES (#{packageDigest}, #{path}, #{blobSha256})
            """)
    int insert(ExtensionFileRow row);

    @Select("""
            SELECT package_digest, path, blob_sha256
              FROM content_extension_file WHERE package_digest = #{packageDigest} AND path = #{path} AND BINARY path = BINARY #{path}
            """)
    Optional<ExtensionFileRow> findByPath(@Param("packageDigest") byte[] packageDigest, @Param("path") String path);
}
