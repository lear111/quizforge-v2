package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadRows.*;

@Mapper
public interface PracticeReadMapper {
    String COLLECTION_COLUMNS = """
            SELECT c.id, c.bank_id, c.title, c.group_name, b.description, b.features, b.listening, b.content_sha256, b.question_count
            FROM practice_collection c JOIN content_bank b ON b.id = c.bank_id
            """;

    @Select(COLLECTION_COLUMNS + " WHERE c.id = #{id} AND c.user_id = #{userId} AND c.deleted_at IS NULL")
    Optional<Collection> collection(@Param("userId") String userId, @Param("id") String id);

    @Select(COLLECTION_COLUMNS + " WHERE c.user_id = #{userId} AND c.deleted_at IS NULL ORDER BY c.created_at, c.id")
    List<Collection> collections(@Param("userId") String userId);

    @Select("""
            SELECT id FROM practice_context
            WHERE collection_id = #{collectionId} AND kind = 'practice' AND scope_key = 'default' AND user_id = #{userId}
            """)
    Optional<String> context(@Param("userId") String userId, @Param("collectionId") String collectionId);

    // INSERT first: no UPDATE of a missing row and no gap-lock upgrade (database design 5.1).
    @Insert("""
            INSERT INTO practice_context (id, user_id, collection_id, kind, scope_key, created_at, updated_at)
            SELECT #{id}, c.user_id, c.id, 'practice', 'default', #{now}, #{now}
            FROM practice_collection c WHERE c.id = #{collectionId} AND c.user_id = #{userId} AND c.deleted_at IS NULL
            """)
    int insertContext(@Param("id") String id, @Param("userId") String userId,
            @Param("collectionId") String collectionId, @Param("now") Instant now);

    // Never select q.data here. State ownership belongs in the JOIN, preserving unanswered questions.
    @Select("""
            SELECT q.question_id AS id, q.title, q.outline, q.listening, q.extension_digest,
                   e.extension_id, e.version AS extension_version, e.name AS extension_name,
                   s.status, s.answer_state, s.result, s.outline_states, s.revision, q.outline_group_index, q.outline_group_name, q.content_sha256, s.score, s.max_score, s.grading_status
            FROM content_question q
            JOIN content_extension_package e ON e.digest = q.extension_digest
            LEFT JOIN practice_question_state s ON s.context_id = #{contextId}
                 AND s.user_id = #{userId} AND s.question_id = q.question_id
            WHERE q.bank_id = #{bankId} ORDER BY q.position
            """)
    List<Summary> questions(@Param("bankId") String bankId, @Param("contextId") String contextId,
            @Param("userId") String userId);

    @Select("""
            SELECT status, answer, answer_state, result, outline_states, projection, revision, score, max_score, grading_status
            FROM practice_question_state WHERE context_id = #{contextId} AND question_id = #{qid}
              AND BINARY question_id = BINARY #{qid} AND user_id = #{userId}
            """)
    Optional<State> state(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId);

    @Select("""
            SELECT draft FROM practice_whiteboard WHERE context_id = #{contextId} AND question_id = #{qid}
              AND BINARY question_id = BINARY #{qid} AND user_id = #{userId}
            """)
    Optional<String> whiteboard(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId);
}
