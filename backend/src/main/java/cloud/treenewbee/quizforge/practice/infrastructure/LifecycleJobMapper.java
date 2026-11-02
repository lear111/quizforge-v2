package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LifecycleJobMapper {
    record Job(String id, String userId, String collectionId, String contextId, String roundId, String kind,
            String status, int total, int completed, String leaseOwner, Instant leaseUntil, String result,
            String errorCode, String errorMessage, Integer archivePosition) {}

    String FIELDS = "id,user_id,collection_id,context_id,round_id,kind,status,total,completed,lease_owner,"
            + "lease_until,result,error_code,error_message,archive_position";

    @Insert("""
            INSERT INTO practice_lifecycle_job
                (id,user_id,collection_id,context_id,round_id,kind,status,total,created_at,updated_at)
            VALUES (#{id},#{userId},#{collectionId},#{contextId},#{roundId},#{kind},'queued',#{total},#{now},#{now})
            """)
    int insert(@Param("id") String id, @Param("userId") String userId, @Param("collectionId") String collectionId,
            @Param("contextId") String contextId, @Param("roundId") String roundId, @Param("kind") String kind,
            @Param("total") int total, @Param("now") Instant now);

    @Select("SELECT " + FIELDS + " FROM practice_lifecycle_job"
            + " WHERE id = #{id} AND user_id = #{userId} AND collection_id = #{collectionId}")
    Optional<Job> find(@Param("id") String id, @Param("userId") String userId, @Param("collectionId") String collectionId);

    @Select("SELECT " + FIELDS + " FROM practice_lifecycle_job"
            + " WHERE available_at <= #{now} AND (status = 'queued' OR lease_until < #{now})"
            + " ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<Job> next(@Param("now") Instant now);

    @Update("""
            UPDATE practice_lifecycle_job SET status = 'running', lease_owner = #{owner},
                lease_until = #{until}, updated_at = #{now} WHERE id = #{id}
            """)
    int claim(@Param("id") String id, @Param("owner") String owner, @Param("until") Instant until,
            @Param("now") Instant now);

    @Update("""
            UPDATE practice_lifecycle_job SET completed = #{done}, lease_until = #{until}, updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'running'
            """)
    int progress(@Param("id") String id, @Param("owner") String owner, @Param("done") int done,
            @Param("until") Instant until, @Param("now") Instant now);

    @Update("""
            UPDATE practice_lifecycle_job SET completed = #{done}, archive_position = #{position},
                lease_until = #{until}, updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'running'
            """)
    int archiveProgress(@Param("id") String id, @Param("owner") String owner, @Param("done") int done,
            @Param("position") int position, @Param("until") Instant until, @Param("now") Instant now);

    @Update("""
            UPDATE practice_lifecycle_job SET status = #{status}, result = #{result}, error_code = #{code},
                error_message = #{message}, lease_until = NULL, updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'running'
            """)
    int end(@Param("id") String id, @Param("owner") String owner, @Param("status") String status,
            @Param("result") String result, @Param("code") String code, @Param("message") String message,
            @Param("now") Instant now);

    @Delete("""
            DELETE FROM practice_lifecycle_job
            WHERE status = #{status} AND status IN ('completed', 'failed') AND updated_at < #{before}
            ORDER BY updated_at, id LIMIT #{limit}
            """)
    int deleteExpired(@Param("status") String status, @Param("before") Instant before, @Param("limit") int limit);

    record Work(String questionId, String answer, long revision) {}

    @Select("""
            SELECT s.question_id, s.answer, s.revision FROM practice_question_state s
            JOIN content_question q ON q.bank_id = #{bankId} AND q.question_id = s.question_id
            WHERE s.context_id = #{contextId} AND s.user_id = #{userId} AND s.status = 'draft'
              AND s.answer IS NOT NULL AND JSON_TYPE(s.answer) != 'NULL' ORDER BY q.position LIMIT 1
            """)
    Optional<Work> nextAnswer(@Param("bankId") String bankId, @Param("contextId") String contextId,
            @Param("userId") String userId);

    @Select("SELECT completion_status FROM practice_context WHERE id = #{contextId} AND user_id = #{userId}")
    Optional<String> phase(@Param("contextId") String contextId, @Param("userId") String userId);

    @Update("""
            UPDATE practice_round SET collection_snapshot = #{snapshot}, updated_at = #{now}
            WHERE id = #{id} AND user_id = #{userId} AND history_frozen_at IS NULL
            """)
    int collection(@Param("id") String id, @Param("userId") String userId, @Param("snapshot") String snapshot,
            @Param("now") Instant now);

    @Update("""
            UPDATE practice_round SET history_frozen_at = #{now}, summary = #{summary}, updated_at = #{now}
            WHERE id = #{id} AND user_id = #{userId} AND history_frozen_at IS NULL AND status = 'completed'
            """)
    int freeze(@Param("id") String id, @Param("userId") String userId, @Param("summary") String summary,
            @Param("now") Instant now);

    record Signals(Integer attemptCount, Boolean firstCorrect, String gradingSource) {}

    @Select("""
            SELECT attempt_count, first_correct, grading_source FROM practice_question_state
            WHERE context_id = #{contextId} AND question_id = #{qid} AND user_id = #{userId}
            """)
    Optional<Signals> signals(@Param("contextId") String contextId, @Param("qid") String qid,
            @Param("userId") String userId);

    @Update("""
            UPDATE practice_lifecycle_job SET status = 'queued', lease_owner = NULL,
                lease_until = NULL, updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'running'
            """)
    int yield(@Param("id") String id, @Param("owner") String owner, @Param("now") Instant now);

    record QuestionCursor(String questionId, int position) {}

    @Select("""
            <script>
            SELECT question_id, position FROM content_question WHERE bank_id = #{bankId}
            <if test="after != null">AND position &gt; #{after}</if>
            ORDER BY position LIMIT 1
            </script>
            """)
    Optional<QuestionCursor> nextQuestion(@Param("bankId") String bankId, @Param("after") Integer after);
}
