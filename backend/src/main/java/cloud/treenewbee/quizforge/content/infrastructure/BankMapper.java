package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BankMapper {
    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM content_bank b WHERE b.id = #{bankId} AND NOT EXISTS (
                SELECT 1 FROM content_question q LEFT JOIN admin_extension_review r
                  ON r.digest = q.extension_digest AND r.status = 'approved'
                 WHERE q.bank_id = b.id AND r.digest IS NULL
              )
            )
            """)
    boolean isAvailable(@Param("bankId") String bankId);

    @Select("""
            SELECT id FROM content_bank WHERE content_sha256 = #{contentSha256} FOR SHARE
            """)
    Optional<String> findIdByContentSha256ForShare(@Param("contentSha256") byte[] contentSha256);

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
