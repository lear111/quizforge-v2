package cloud.treenewbee.quizforge.ai.grading;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiGradingMapper {
    @Update("UPDATE ai_grading_task SET status='confirmed',error_code=NULL,error_message=NULL,updated_at=#{now} WHERE user_id=#{user} AND id=#{id} AND candidate_version=#{version} AND status='succeeded'")
    int confirm(@Param("user") String user, @Param("id") String id, @Param("version") String version, @Param("now") Instant now);

    @Update("UPDATE ai_grading_task SET status=IF(checkpoint IS NULL,'queued','model_returned'),locked_until=NULL,next_attempt_at=#{next},updated_at=#{now} WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status IN ('running','model_returned')")
    int yield(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts, @Param("next") Instant next, @Param("now") Instant now);
    String FIELDS = "id,user_id,kind,collection_id,context_id,question_id,target_id,round_id,submission_generation,grading_generation,answer_sha256,content_sha256,status,priority,automatic,attempts,model_calls,repair_used,next_attempt_at,locked_until,input_json,checkpoint,candidate,candidate_version,cost,error_code,error_message,created_at,updated_at";

    @Select("SELECT " + FIELDS + " FROM ai_grading_task WHERE user_id=#{user} AND id=#{id}")
    Optional<AiGradingTask> find(@Param("user") String user, @Param("id") String id);

    @Select("SELECT " + FIELDS + " FROM ai_grading_task WHERE user_id=#{user} AND id=#{id} FOR UPDATE")
    Optional<AiGradingTask> lock(@Param("user") String user, @Param("id") String id);

    @Select("SELECT " + FIELDS + " FROM ai_grading_task WHERE status IN ('queued','model_returned') AND next_attempt_at<=#{now} AND (locked_until IS NULL OR locked_until<=#{now}) ORDER BY priority,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<AiGradingTask> next(Instant now);

    @Update("UPDATE ai_grading_task SET status=IF(checkpoint IS NULL,'queued','model_returned'),locked_until=NULL,next_attempt_at=#{now},updated_at=#{now} WHERE status='running' AND locked_until<=#{now} LIMIT 100")
    int recover(Instant now);

    @Update("UPDATE ai_grading_task SET status='running',attempts=attempts+1,locked_until=#{until},updated_at=#{now} WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status IN ('queued','model_returned')")
    int claim(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts,
            @Param("until") Instant until, @Param("now") Instant now);

    @Insert("""
        INSERT INTO ai_grading_task(id,user_id,kind,collection_id,context_id,question_id,target_id,round_id,
          submission_generation,grading_generation,answer_sha256,content_sha256,status,priority,automatic,
          next_attempt_at,input_json,created_at,updated_at)
        VALUES(#{id},#{userId},#{kind},#{collectionId},#{contextId},#{questionId},#{targetId},#{roundId},
          #{submissionGeneration},#{gradingGeneration},#{answerSha256},#{contentSha256},'queued',#{priority},#{automatic},
          #{nextAttemptAt},#{inputJson},#{createdAt},#{updatedAt})
        """)
    int insert(AiGradingTask task);

    @Select("""
        SELECT t.* FROM ai_grading_task t JOIN ai_grading_binding b ON b.task_id=t.id
        WHERE b.context_id=#{context} AND b.question_id=#{qid} AND b.target_id=#{target} AND b.user_id=#{user}
        """)
    Optional<AiGradingTask> current(@Param("context") String context, @Param("qid") String qid,
            @Param("target") String target, @Param("user") String user);

    @Delete("""
        DELETE FROM ai_grading_binding WHERE context_id=#{context} AND question_id=#{qid} AND user_id=#{user}
          AND (#{whole}=TRUE OR target_id='')
        """)
    int replaceBindings(@Param("context") String context, @Param("qid") String qid,
            @Param("user") String user, @Param("whole") boolean whole);

    @Insert("""
        INSERT INTO ai_grading_binding(context_id,question_id,target_id,task_id,user_id)
        VALUES(#{contextId},#{questionId},#{targetId},#{id},#{userId})
        ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)
        """)
    int bind(AiGradingTask task);

    @Update("""
        UPDATE ai_grading_task SET status='superseded',error_code='AI_TASK_SUPERSEDED',
          error_message='作答或评分已变化，本次任务不再采用',updated_at=#{now}
        WHERE context_id=#{context} AND user_id=#{user} AND status='queued' AND checkpoint IS NULL
          AND (#{qid} IS NULL OR question_id=#{qid})
        """)
    int cancelQueued(@Param("context") String context, @Param("qid") String qid,
            @Param("user") String user, @Param("now") Instant now);

    @Update("UPDATE ai_grading_task SET model_calls=model_calls+1,updated_at=#{now} WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status='running' AND model_calls<3 AND locked_until>#{now}")
    int beforeCall(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts, @Param("now") Instant now);

    @Update("UPDATE ai_grading_task SET checkpoint=#{checkpoint},status='model_returned',updated_at=#{now} WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status='running' AND locked_until>#{now}")
    int checkpoint(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts,
            @Param("checkpoint") String checkpoint, @Param("now") Instant now);

    @Update("UPDATE ai_grading_task SET input_json=#{input},updated_at=#{now} WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status='running'")
    int input(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts,
            @Param("input") String input, @Param("now") Instant now);

    @Update("""
        UPDATE ai_grading_task SET status=#{status},candidate=#{candidate},candidate_version=#{version},
          checkpoint=#{checkpoint},cost=#{cost},error_code=#{code},error_message=#{message},updated_at=#{now},locked_until=NULL
        WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status IN ('running','model_returned')
        """)
    int finish(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts,
            @Param("status") String status, @Param("candidate") String candidate, @Param("version") String version,
            @Param("checkpoint") String checkpoint, @Param("cost") long cost,
            @Param("code") String code, @Param("message") String message, @Param("now") Instant now);

    @Update("""
        UPDATE ai_grading_task SET status='queued',checkpoint=NULL,repair_used=#{repair},next_attempt_at=#{next},
          locked_until=NULL,error_code=#{code},error_message=#{message},cost=#{cost},updated_at=#{now}
        WHERE id=#{id} AND user_id=#{user} AND attempts=#{attempts} AND status IN ('running','model_returned')
        """)
    int retry(@Param("id") String id, @Param("user") String user, @Param("attempts") int attempts,
            @Param("repair") boolean repair, @Param("next") Instant next, @Param("code") String code,
            @Param("message") String message, @Param("cost") long cost, @Param("now") Instant now);

    @Select("SELECT COALESCE((SELECT auto_grade FROM ai_grading_setting WHERE user_id=#{user}),FALSE)")
    boolean autoGrade(String user);

    @Insert("INSERT INTO ai_grading_setting(user_id,auto_grade,updated_at) VALUES(#{user},#{value},#{now}) ON DUPLICATE KEY UPDATE auto_grade=VALUES(auto_grade),updated_at=VALUES(updated_at)")
    int setting(@Param("user") String user, @Param("value") boolean value, @Param("now") Instant now);
}
