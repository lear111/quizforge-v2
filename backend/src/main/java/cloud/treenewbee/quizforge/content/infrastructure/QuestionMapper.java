package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuestionMapper {
    @Insert("""
            INSERT INTO content_question (bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256)
            VALUES (#{bankId}, #{questionId}, #{position}, #{title}, #{extensionDigest}, #{outline}, #{listening}, #{data}, #{maxScore}, #{contentSha256})
            """)
    int insert(QuestionRow row);

    @Select("""
            SELECT bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256
              FROM content_question WHERE bank_id = #{bankId} AND question_id = #{questionId} AND BINARY question_id = BINARY #{questionId}
            """)
    Optional<QuestionRow> findById(@Param("bankId") String bankId, @Param("questionId") String questionId);

    @Select("""
            SELECT bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256
              FROM content_question WHERE bank_id = #{bankId} AND position = #{position}
            """)
    Optional<QuestionRow> findByPosition(@Param("bankId") String bankId, @Param("position") int position);
}
