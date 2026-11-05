package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 广场题库实例的版本切换（设计 04 第 3 节）。所有语句都带 user_id 条件；加锁顺序 context → collection（与其他写操作一致，见 PracticeUpdates.open）。
 */
@Mapper
public interface PracticeUpdateMapper {

    record Source(String id, String bankId, String sourcePackId, String sourceBankUuid, Integer sourceRevision,
                  String updateStatus) {
    }

    String SOURCE = """
            SELECT id, bank_id, source_pack_id, source_bank_uuid, source_revision, update_status FROM practice_collection
             WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL""";

    @Select(SOURCE)
    Optional<Source> source(@Param("userId") String userId, @Param("id") String id);

    /** 锁住题库实例（已存在的行，按主键）：同一用户两个页面同时触发切换时只切换一次。 */
    @Select(SOURCE + " FOR UPDATE")
    Optional<Source> lockSource(@Param("userId") String userId, @Param("id") String id);

    /** 用户所有来自广场、未删除的实例（练习空间题库列表用）。 */
    @Select("""
            SELECT id, bank_id, source_pack_id, source_bank_uuid, source_revision, update_status FROM practice_collection
             WHERE user_id = #{userId} AND deleted_at IS NULL AND source_pack_id IS NOT NULL
            """)
    List<Source> sources(@Param("userId") String userId);

    /** 普通练习存档的 ID（不加锁；存档不存在时说明从未打开，也就没有作答）。 */
    @Select("SELECT id FROM practice_context WHERE collection_id = #{collectionId} AND user_id = #{userId} AND kind = 'practice'")
    Optional<String> practiceContext(@Param("collectionId") String collectionId, @Param("userId") String userId);

    /**
     * 是否有进行中的作答（第 3.1 节）：存档中有 draft / submitted 的作答、存档不处于进行中（已完成未再练一次、
     * 正在完成或归档）、白板上有内容，或该题库有进行中 / 评分中的考试。调用方已锁住实例与存档。
     */
    @Select("""
            SELECT (EXISTS (SELECT 1 FROM practice_question_state s
                             WHERE s.context_id = #{contextId} AND s.user_id = #{userId} AND s.status IN ('draft', 'submitted'))
                 OR EXISTS (SELECT 1 FROM practice_context x
                             WHERE x.id = #{contextId} AND x.user_id = #{userId}
                               AND (x.completion_status <> 'active' OR x.lifecycle_job_id IS NOT NULL))
                 OR EXISTS (SELECT 1 FROM practice_whiteboard w
                             WHERE w.context_id = #{contextId} AND w.user_id = #{userId} AND w.size_bytes > 0))
            """)
    boolean practiceInProgress(@Param("contextId") String contextId, @Param("userId") String userId);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM exam_exam
                            WHERE collection_id = #{collectionId} AND user_id = #{userId} AND status IN ('in_progress', 'grading'))
            """)
    boolean examInProgress(@Param("collectionId") String collectionId, @Param("userId") String userId);

    /** 切换到新版本：只改实例指向的内容与版本，标题与分组保留；条件中再次确认版本未变。 */
    @Update("""
            UPDATE practice_collection
               SET bank_id = #{bankId}, source_revision = #{revision}, update_status = 'current',
                   source = JSON_SET(source, '$.revision', #{revision})
             WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL AND source_revision = #{expected}
               AND update_status <> 'removed'
            """)
    int switchTo(@Param("id") String id, @Param("userId") String userId, @Param("bankId") String bankId,
                 @Param("revision") int revision, @Param("expected") int expected);

    /** 新版本中没有这本题库：停在当前版本，此后不再更新。 */
    @Update("""
            UPDATE practice_collection SET update_status = 'removed'
             WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL AND source_revision = #{expected}
               AND update_status <> 'removed'
            """)
    int markRemoved(@Param("id") String id, @Param("userId") String userId, @Param("expected") int expected);

    @Update("""
            UPDATE practice_context SET progress_version = progress_version + 1, updated_at = #{now}
             WHERE id = #{contextId} AND user_id = #{userId}
            """)
    int bump(@Param("contextId") String contextId, @Param("userId") String userId, @Param("now") Instant now);
}
