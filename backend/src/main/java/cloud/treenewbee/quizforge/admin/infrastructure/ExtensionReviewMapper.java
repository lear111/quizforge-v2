package cloud.treenewbee.quizforge.admin.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExtensionReviewMapper {
    @Insert("""
            INSERT INTO admin_extension_review (digest, extension_id, version, status, reviewer, reviewed_at, reason)
            VALUES (#{digest}, #{extensionId}, #{version}, #{status}, #{reviewer}, #{reviewedAt}, #{reason})
            """)
    int insert(ExtensionReviewRow row);

    @Select("""
            SELECT digest, extension_id, version, status, reviewer, reviewed_at, reason
              FROM admin_extension_review WHERE digest = #{digest}
            """)
    Optional<ExtensionReviewRow> findByDigest(@Param("digest") byte[] digest);
}
