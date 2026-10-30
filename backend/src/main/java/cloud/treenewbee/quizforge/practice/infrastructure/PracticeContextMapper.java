package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PracticeContextMapper {
    /** The caller must keep a transaction open until all dependent writes finish. */
    @Select("""
            SELECT id, current_round_id, progress_version FROM practice_context
            WHERE id = #{contextId} AND user_id = #{userId} FOR UPDATE
            """)
    Optional<PracticeContextRow> lockForUpdate(@Param("contextId") String contextId, @Param("userId") String userId);

    @Update("""
            UPDATE practice_context c SET current_round_id = #{roundId}, updated_at = #{now}
            WHERE c.id = #{contextId} AND c.user_id = #{userId}
              AND (#{roundId} IS NULL OR EXISTS (
                SELECT 1 FROM practice_round r WHERE r.id = #{roundId} AND r.context_id = c.id AND r.user_id = c.user_id))
            """)
    int setCurrentRound(@Param("contextId") String contextId, @Param("userId") String userId,
            @Param("roundId") String roundId, @Param("now") Instant now);

    @Update("""
            UPDATE practice_context SET progress_version = progress_version + 1, updated_at = #{now}
            WHERE id = #{contextId} AND user_id = #{userId}
            """)
    int bumpProgressVersion(@Param("contextId") String contextId, @Param("userId") String userId, @Param("now") Instant now);
}
