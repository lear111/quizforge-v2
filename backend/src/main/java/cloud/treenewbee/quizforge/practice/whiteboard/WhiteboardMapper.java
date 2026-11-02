package cloud.treenewbee.quizforge.practice.whiteboard;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WhiteboardMapper {
    record Row(String contextId, String questionId, String userId, String draft, String notes, int sizeBytes, long revision, Instant updatedAt) {}

    String FIND = """
            SELECT context_id, question_id, user_id, draft, notes, size_bytes, revision, updated_at
            FROM practice_whiteboard WHERE context_id = #{contextId} AND question_id = #{qid}
              AND BINARY question_id = BINARY #{qid} AND user_id = #{userId}
            """;
    @Select(FIND)
    Optional<Row> find(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId);

    @Select(FIND + " FOR UPDATE")
    Optional<Row> lock(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM practice_question_state
              WHERE context_id = #{contextId} AND question_id = #{qid}
                AND BINARY question_id = BINARY #{qid} AND user_id = #{userId} AND status = 'submitted')
            """)
    boolean submitted(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId);

    @Insert("""
            INSERT INTO practice_whiteboard (context_id, question_id, user_id, draft, notes, size_bytes, revision, updated_at)
            VALUES (#{contextId}, #{questionId}, #{userId}, #{draft}, #{notes}, #{sizeBytes}, #{revision}, #{updatedAt})
            """)
    int insert(Row row);

    @Update("""
            UPDATE practice_whiteboard SET draft = #{draft}, notes = #{notes}, size_bytes = #{sizeBytes},
              revision = revision + 1, updated_at = #{updatedAt}
            WHERE context_id = #{contextId} AND question_id = #{questionId} AND BINARY question_id = BINARY #{questionId}
              AND user_id = #{userId} AND revision = #{revision}
            """)
    int update(Row expected);
}
