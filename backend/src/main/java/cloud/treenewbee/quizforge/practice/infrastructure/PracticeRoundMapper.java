package cloud.treenewbee.quizforge.practice.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PracticeRoundMapper {
    @Insert("""
            INSERT INTO practice_round (id, user_id, collection_id, context_id, mode, status,
                collection_snapshot, summary, created_at, updated_at, completed_at, deleted_at)
            SELECT #{id}, #{userId}, #{collectionId}, #{contextId}, #{mode}, #{status},
                #{collectionSnapshot}, #{summary}, #{createdAt}, #{updatedAt}, #{completedAt}, #{deletedAt}
            FROM practice_context c WHERE c.id = #{contextId} AND c.user_id = #{userId} AND c.collection_id = #{collectionId}
            """)
    int insertRound(PracticeRoundRow row);

    @Insert("""
            INSERT INTO practice_round_question (round_id, question_id, user_id, payload, extension_digest, updated_at)
            SELECT #{roundId}, #{questionId}, #{userId}, #{payload}, #{extensionDigest}, #{updatedAt}
            FROM practice_round r WHERE r.id = #{roundId} AND r.user_id = #{userId}
            ON DUPLICATE KEY UPDATE payload = #{payload}, extension_digest = #{extensionDigest}, updated_at = #{updatedAt}
            """)
    int upsertRoundQuestion(PracticeRoundQuestionRow row);
}
