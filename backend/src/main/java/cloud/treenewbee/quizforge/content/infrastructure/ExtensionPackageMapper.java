package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExtensionPackageMapper {
    @Insert("""
            INSERT IGNORE INTO content_extension_package (digest, extension_id, version, name, manifest, api_major, api_min_minor, created_at)
            VALUES (#{digest}, #{extensionId}, #{version}, #{name}, #{manifest}, #{apiMajor}, #{apiMinMinor}, #{createdAt})
            """)
    int insertIfAbsent(ExtensionPackageRow row);

    @Insert("""
            INSERT INTO content_extension_package (digest, extension_id, version, name, manifest, api_major, api_min_minor, created_at)
            VALUES (#{digest}, #{extensionId}, #{version}, #{name}, #{manifest}, #{apiMajor}, #{apiMinMinor}, #{createdAt})
            """)
    int insert(ExtensionPackageRow row);

    @Select("""
            SELECT digest, extension_id, version, name, manifest, api_major, api_min_minor, created_at
              FROM content_extension_package WHERE digest = #{digest}
            """)
    Optional<ExtensionPackageRow> findByDigest(@Param("digest") byte[] digest);
}
