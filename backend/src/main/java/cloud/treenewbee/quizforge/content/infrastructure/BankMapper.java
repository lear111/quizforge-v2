package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BankMapper {
    @Insert("""
            INSERT INTO content_bank (id, content_sha256, source_title, description, format_version, features, listening, question_count, created_at)
            VALUES (#{id}, #{contentSha256}, #{sourceTitle}, #{description}, #{formatVersion}, #{features}, #{listening}, #{questionCount}, #{createdAt})
            """)
    int insert(BankRow row);

    @Select("""
            SELECT id, content_sha256, source_title, description, format_version, features, listening, question_count, created_at
              FROM content_bank WHERE id = #{id}
            """)
    Optional<BankRow> findById(@Param("id") String id);

    @Select("""
            SELECT id, content_sha256, source_title, description, format_version, features, listening, question_count, created_at
              FROM content_bank WHERE content_sha256 = #{contentSha256}
            """)
    Optional<BankRow> findByContentSha256(@Param("contentSha256") byte[] contentSha256);
}
