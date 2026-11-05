package cloud.treenewbee.quizforge.exam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ExamMapper {
    String FIELDS = "e.id,e.user_id,e.collection_id,e.context_id,e.bank_id,e.status,e.duration_minutes,e.started_at,e.deadline_at,e.submitted_at,e.round_id,e.create_request_id,e.version,e.title";
    String OWNED = " FROM exam_exam e JOIN practice_collection c ON c.id=e.collection_id AND c.user_id=e.user_id WHERE c.deleted_at IS NULL AND e.user_id=#{userId}";

    @Select("SELECT " + FIELDS + OWNED + " AND e.id=#{id}")
    Optional<ExamRow> find(@Param("userId") String userId, @Param("id") String id);

    @Select("SELECT " + FIELDS + OWNED + " AND e.create_request_id=#{requestId}")
    Optional<ExamRow> creation(@Param("userId") String userId, @Param("requestId") String requestId);

    @Select("SELECT " + FIELDS + OWNED + " AND e.context_id=#{contextId}")
    Optional<ExamRow> context(@Param("userId") String userId, @Param("contextId") String contextId);
    // 调用前先锁已有 context，所有阶段转换保持同一加锁顺序。

    @Select("SELECT " + FIELDS + " FROM exam_exam e WHERE e.id=#{id} AND e.user_id=#{userId} FOR UPDATE")
    Optional<ExamRow> lock(@Param("userId") String userId, @Param("id") String id);

    @Select("SELECT " + FIELDS + OWNED + " AND e.collection_id=#{collectionId} AND e.status IN ('in_progress','grading') ORDER BY e.started_at DESC,e.id")
    List<ExamRow> list(@Param("userId") String userId, @Param("collectionId") String collectionId);

    // 只锁已存在且属于本人的题库实例，串行化同一本题库的开考请求。
    @Select("SELECT id FROM practice_collection WHERE id=#{collectionId} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
    Optional<String> lockCollection(@Param("userId") String userId, @Param("collectionId") String collectionId);

    @Select("SELECT COUNT(*) FROM exam_exam WHERE user_id=#{userId} AND collection_id=#{collectionId} AND status='in_progress'")
    int inProgress(@Param("userId") String userId, @Param("collectionId") String collectionId);

    @Select("SELECT " + FIELDS + OWNED + " AND e.collection_id=#{collectionId} ORDER BY e.started_at DESC,e.id DESC LIMIT 1")
    Optional<ExamRow> latest(@Param("userId") String userId, @Param("collectionId") String collectionId);

    @Insert("""
        INSERT INTO practice_context(id,user_id,collection_id,kind,scope_key,created_at,updated_at)
        SELECT #{contextId},user_id,id,'exam',#{id},#{now},#{now} FROM practice_collection
        WHERE id=#{collectionId} AND user_id=#{userId} AND deleted_at IS NULL
        """)
    int insertContext(@Param("contextId") String contextId, @Param("userId") String userId,
        @Param("collectionId") String collectionId, @Param("id") String id, @Param("now") Instant now);

    @Insert("""
        INSERT INTO exam_exam(id,user_id,collection_id,context_id,bank_id,status,duration_minutes,started_at,deadline_at,create_request_id,title)
        SELECT #{id},c.user_id,c.id,#{contextId},#{bankId},'in_progress',#{durationMinutes},#{startedAt},#{deadlineAt},#{createRequestId},#{title}
        FROM practice_collection c WHERE c.id=#{collectionId} AND c.user_id=#{userId} AND c.deleted_at IS NULL
        """)
    int insert(ExamRow row);

    @Update("""
        UPDATE exam_exam SET status='grading',submitted_at=COALESCE(submitted_at,#{now}),round_id=#{roundId},version=version+1
        WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND status IN ('in_progress','grading')
        """)
    int submit(@Param("id") String id, @Param("userId") String userId, @Param("version") long version,
        @Param("roundId") String roundId, @Param("now") Instant now);

    @Update("UPDATE exam_exam SET status='completed',version=version+1 WHERE context_id=#{contextId} AND user_id=#{userId} AND status='grading'")
    int complete(@Param("contextId") String contextId, @Param("userId") String userId);

    @Update("UPDATE exam_exam SET status='deleted',version=version+1 WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND status IN ('in_progress','grading')")
    int delete(@Param("id") String id, @Param("userId") String userId, @Param("version") long version);
    record Due(String id, String userId) {
    }
    // status 为等值前缀，deadline_at 为范围；有界索引扫描，不遍历历史考试。

    @Select("SELECT id,user_id FROM exam_exam FORCE INDEX(idx_exam_deadline) WHERE status='in_progress' AND deadline_at <= #{now} ORDER BY deadline_at,id LIMIT #{limit}")
    List<Due> due(@Param("now") Instant now, @Param("limit") int limit);

    @Select("SELECT id,current_round_id,progress_version,completion_status,lifecycle_job_id FROM practice_context WHERE id=#{contextId} AND user_id=#{userId}")
    Optional<cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextRow> phase(@Param("contextId") String contextId, @Param("userId") String userId);

    @Delete("DELETE FROM practice_question_state WHERE context_id=#{contextId} AND user_id=#{userId}")
    int purgeStates(@Param("contextId") String contextId, @Param("userId") String userId);

    @Delete("DELETE FROM practice_whiteboard WHERE context_id=#{contextId} AND user_id=#{userId}")
    int purgeWhiteboards(@Param("contextId") String contextId, @Param("userId") String userId);

    @Delete("DELETE p FROM practice_round_question p JOIN practice_round r ON r.id=p.round_id AND r.user_id=p.user_id WHERE r.context_id=#{contextId} AND r.user_id=#{userId}")
    int purgePayloads(@Param("contextId") String contextId, @Param("userId") String userId);

    @Delete("DELETE FROM practice_lifecycle_job WHERE context_id=#{contextId} AND user_id=#{userId}")
    int purgeJobs(@Param("contextId") String contextId, @Param("userId") String userId);

    @Delete("DELETE FROM practice_round WHERE context_id=#{contextId} AND user_id=#{userId}")
    int purgeRounds(@Param("contextId") String contextId, @Param("userId") String userId);
}
