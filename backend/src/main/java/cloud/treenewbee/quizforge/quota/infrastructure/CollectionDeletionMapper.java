package cloud.treenewbee.quizforge.quota.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CollectionDeletionMapper {
    @Select("SELECT id FROM practice_collection WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL FOR UPDATE")
    Optional<String> lock(@Param("userId") String userId, @Param("id") String id);

    @Select("SELECT id FROM practice_context WHERE collection_id = #{id} AND user_id = #{userId} ORDER BY id FOR UPDATE")
    List<String> contexts(@Param("userId") String userId, @Param("id") String id);

    @Select("""
            SELECT s.question_id FROM practice_question_state s
            WHERE s.user_id = #{userId} AND s.context_id = #{contextId} ORDER BY s.question_id FOR UPDATE
            """)
    List<String> states(@Param("userId") String userId, @Param("contextId") String contextId);

    @Delete("""
            DELETE q FROM practice_round_question q JOIN practice_round r ON r.id = q.round_id AND r.user_id = q.user_id
            WHERE r.collection_id = #{id} AND r.user_id = #{userId}
            """)
    int snapshots(@Param("userId") String userId, @Param("id") String id);

    @Delete("DELETE FROM practice_round WHERE collection_id = #{id} AND user_id = #{userId}")
    int rounds(@Param("userId") String userId, @Param("id") String id);

    @Delete("""
            DELETE w FROM practice_whiteboard w JOIN practice_context c ON c.id = w.context_id AND c.user_id = w.user_id
            WHERE c.collection_id = #{id} AND c.user_id = #{userId}
            """)
    int whiteboards(@Param("userId") String userId, @Param("id") String id);

    @Delete("""
            DELETE s FROM practice_question_state s JOIN practice_context c ON c.id = s.context_id AND c.user_id = s.user_id
            WHERE c.collection_id = #{id} AND c.user_id = #{userId}
            """)
    int statesInCollection(@Param("userId") String userId, @Param("id") String id);

    @Delete("DELETE FROM exam_exam WHERE collection_id = #{id} AND user_id = #{userId}")
    int exams(@Param("userId") String userId, @Param("id") String id);

    @Delete("DELETE FROM practice_context WHERE collection_id = #{id} AND user_id = #{userId}")
    int deleteContexts(@Param("userId") String userId, @Param("id") String id);

    @Update("UPDATE practice_collection SET deleted_at = #{now} WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int markDeleted(@Param("userId") String userId, @Param("id") String id, @Param("now") Instant now);
    @Delete("DELETE a FROM practice_history_annotation a JOIN practice_round r ON r.id=a.history_id WHERE r.user_id=#{userId} AND r.collection_id=#{id}")
    int annotations(@Param("userId") String userId,@Param("id") String id);
    @Delete("DELETE a FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id WHERE r.user_id=#{userId} AND r.collection_id=#{id}")
    int historyAssets(@Param("userId") String userId,@Param("id") String id);
    @Delete("DELETE FROM practice_lifecycle_job WHERE user_id=#{userId} AND collection_id=#{id}")
    int jobs(@Param("userId") String userId,@Param("id") String id);
}
