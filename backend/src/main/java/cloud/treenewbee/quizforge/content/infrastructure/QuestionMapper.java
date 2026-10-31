package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuestionMapper {
    /** The caller supplies a nonempty batch; duplicate keys fail the whole statement. */
    @Insert("""
            <script>
            INSERT INTO content_question (bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256, outline_group_index, outline_group_name)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{row.bankId}, #{row.questionId}, #{row.position}, #{row.title}, #{row.extensionDigest},
               #{row.outline}, #{row.listening}, #{row.data}, #{row.maxScore}, #{row.contentSha256}, #{row.outlineGroupIndex}, #{row.outlineGroupName})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<QuestionRow> rows);

    @Insert("""
            INSERT INTO content_question (bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256, outline_group_index, outline_group_name)
            VALUES (#{bankId}, #{questionId}, #{position}, #{title}, #{extensionDigest}, #{outline}, #{listening}, #{data}, #{maxScore}, #{contentSha256}, #{outlineGroupIndex}, #{outlineGroupName})
            """)
    int insert(QuestionRow row);

    @Select("""
            SELECT bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256, outline_group_index, outline_group_name
              FROM content_question WHERE bank_id = #{bankId} AND question_id = #{questionId} AND BINARY question_id = BINARY #{questionId}
            """)
    Optional<QuestionRow> findById(@Param("bankId") String bankId, @Param("questionId") String questionId);

    @Select("""
            SELECT bank_id, question_id, position, title, extension_digest, outline, listening, data, max_score, content_sha256, outline_group_index, outline_group_name
              FROM content_question WHERE bank_id = #{bankId} AND position = #{position}
            """)
    Optional<QuestionRow> findByPosition(@Param("bankId") String bankId, @Param("position") int position);
}
