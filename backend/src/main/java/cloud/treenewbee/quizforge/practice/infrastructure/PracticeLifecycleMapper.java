package cloud.treenewbee.quizforge.practice.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Lifecycle writes follow the context → state → round lock order. */
@Mapper
public interface PracticeLifecycleMapper {
    record Totals(long questionCount, long submittedCount, long pendingCount, BigDecimal score, BigDecimal maxScore) {}
    record SummaryQuestion(String id, String status, String gradingStatus, BigDecimal score, BigDecimal maxScore,
            long revision, boolean answered, String outlineStates) {}

    @Select("""
            <script>
            SELECT question_id FROM practice_question_state WHERE context_id = #{contextId} AND user_id = #{userId}
            <if test="qid != null">AND question_id = #{qid}</if>
            ORDER BY question_id FOR UPDATE
            </script>
            """)
    List<String> lockStates(@Param("contextId") String contextId, @Param("userId") String userId, @Param("qid") String qid);

    @Select("""
            SELECT COUNT(*) AS question_count,
                   COALESCE(SUM(COALESCE(s.status = 'submitted', 0)), 0) AS submitted_count,
                   COALESCE(SUM(COALESCE(s.grading_status = 'pending', 0)), 0) AS pending_count,
                   COALESCE(SUM(CASE WHEN s.grading_status = 'graded' THEN s.score ELSE 0 END), 0) AS score,
                   COALESCE(SUM(COALESCE(s.max_score, q.max_score)), 0) AS max_score
            FROM content_question q LEFT JOIN practice_question_state s
              ON s.context_id = #{contextId} AND s.user_id = #{userId} AND s.question_id = q.question_id
            WHERE q.bank_id = #{bankId}
            """)
    Totals totals(@Param("bankId") String bankId, @Param("contextId") String contextId, @Param("userId") String userId);

    @Select("""
            SELECT q.question_id AS id, COALESCE(s.status, 'unanswered') AS status,
                   COALESCE(s.grading_status, 'none') AS grading_status, s.score,
                   COALESCE(s.max_score, q.max_score) AS max_score, COALESCE(s.revision, 0) AS revision,
                   COALESCE(s.status = 'draft' AND s.answer IS NOT NULL AND JSON_TYPE(s.answer) <> 'NULL', 0) AS answered,
                   s.outline_states
            FROM content_question q LEFT JOIN practice_question_state s
              ON s.context_id = #{contextId} AND s.user_id = #{userId} AND s.question_id = q.question_id
            WHERE q.bank_id = #{bankId} ORDER BY q.position
            """)
    List<SummaryQuestion> summaryQuestions(@Param("bankId") String bankId, @Param("contextId") String contextId,
            @Param("userId") String userId);

    @Select("""
            SELECT id, current_round_id, progress_version FROM practice_context
            WHERE id = #{contextId} AND user_id = #{userId}
            """)
    Optional<PracticeContextRow> context(@Param("contextId") String contextId, @Param("userId") String userId);

    @Select("""
            SELECT id, user_id, collection_id, context_id, mode, status, collection_snapshot, summary,
                   created_at, updated_at, completed_at, deleted_at
            FROM practice_round WHERE id = #{roundId} AND user_id = #{userId} AND context_id = #{contextId}
              AND deleted_at IS NULL
            """)
    Optional<PracticeRoundRow> round(@Param("roundId") String roundId, @Param("contextId") String contextId,
            @Param("userId") String userId);

    @Select("""
            SELECT payload FROM practice_round_question
            WHERE round_id = #{roundId} AND question_id = #{qid} AND BINARY question_id = BINARY #{qid}
              AND user_id = #{userId}
            """)
    Optional<String> payload(@Param("roundId") String roundId, @Param("qid") String qid, @Param("userId") String userId);

    @Update("""
            UPDATE practice_question_state SET status = 'unanswered', answer = NULL, answer_state = NULL,
                result = NULL, score = NULL, max_score = NULL, grading_status = 'none', outline_states = NULL,
                projection = NULL, revision = revision + 1, grading_generation = grading_generation + 1,
                submission_generation = submission_generation + 1, updated_at = #{now}
            WHERE context_id = #{contextId} AND question_id = #{qid} AND user_id = #{userId}
              AND revision = #{revision} AND status = 'submitted'
            """)
    int retry(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId,
            @Param("revision") long revision, @Param("now") Instant now);

    @Update("""
            UPDATE practice_question_state SET result = #{result}, score = #{score}, max_score = #{maxScore},
                grading_status = #{gradingStatus}, outline_states = #{outlineStates}, projection = #{projection},
                revision = revision + 1, grading_generation = grading_generation + 1, updated_at = #{now}
            WHERE context_id = #{contextId} AND question_id = #{qid} AND user_id = #{userId}
              AND revision = #{revision} AND status = 'submitted'
            """)
    int review(@Param("contextId") String contextId, @Param("qid") String qid, @Param("userId") String userId,
            @Param("revision") long revision, @Param("result") String result, @Param("score") BigDecimal score,
            @Param("maxScore") BigDecimal maxScore, @Param("gradingStatus") String gradingStatus,
            @Param("outlineStates") String outlineStates, @Param("projection") String projection, @Param("now") Instant now);

    @Update("""
            UPDATE practice_question_state SET status = 'unanswered', answer = NULL, answer_state = NULL,
                result = NULL, score = NULL, max_score = NULL, grading_status = 'none', outline_states = NULL,
                projection = NULL, revision = revision + 1, grading_generation = grading_generation + 1,
                submission_generation = submission_generation + 1, updated_at = #{now}
            WHERE context_id = #{contextId} AND user_id = #{userId}
            """)
    int reset(@Param("contextId") String contextId, @Param("userId") String userId, @Param("now") Instant now);

    @Delete("DELETE FROM practice_whiteboard WHERE context_id = #{contextId} AND user_id = #{userId}")
    int deleteWhiteboards(@Param("contextId") String contextId, @Param("userId") String userId);

    @Update("""
            UPDATE practice_round_question SET payload = #{payload}, updated_at = #{now}
            WHERE round_id = #{roundId} AND question_id = #{qid} AND user_id = #{userId}
            """)
    int updatePayload(@Param("roundId") String roundId, @Param("qid") String qid, @Param("userId") String userId,
            @Param("payload") String payload, @Param("now") Instant now);

    @Update("""
            UPDATE practice_round SET summary = #{summary}, updated_at = #{now}
            WHERE id = #{roundId} AND user_id = #{userId} AND status = 'completed'
            """)
    int updateCompletedSummary(@Param("roundId") String roundId, @Param("userId") String userId,
            @Param("summary") String summary, @Param("now") Instant now);

    @Update("""
            UPDATE practice_round SET status = 'completed', summary = #{summary}, completed_at = #{now}, updated_at = #{now}
            WHERE id = #{roundId} AND user_id = #{userId} AND status = 'in_progress'
            """)
    int complete(@Param("roundId") String roundId, @Param("userId") String userId,
            @Param("summary") String summary, @Param("now") Instant now);
}
