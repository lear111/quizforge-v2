package cloud.treenewbee.quizforge.practice.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PracticeAiMapper {
    @Select("""
        SELECT EXISTS(SELECT 1 FROM practice_context c
          JOIN practice_collection p ON p.id=c.collection_id AND p.user_id=c.user_id AND p.deleted_at IS NULL
          LEFT JOIN exam_exam x ON x.context_id=c.id AND x.user_id=c.user_id AND x.status!='deleted'
          JOIN content_question q ON q.bank_id=COALESCE(x.bank_id,p.bank_id) AND q.question_id=#{qid}
          JOIN content_extension_package e ON e.digest=q.extension_digest
          WHERE c.id=#{context} AND c.user_id=#{user}
            AND JSON_CONTAINS(e.manifest,JSON_QUOTE('ai-grading'),'$.requiresApi.capabilities'))
        """)
    boolean supportsAi(@Param("context") String context, @Param("user") String user, @Param("qid") String qid);
    record Target(String contextId, String userId, String collectionId, String kind, String scopeKey,
            String roundId, String questionId, String status, String gradingStatus, String answer,
            String result, long revision, int submissionGeneration, int gradingGeneration, byte[] contentSha256,
            String completionStatus) {}

    @Select("""
        SELECT c.id context_id,c.user_id,c.collection_id,c.kind,c.scope_key,c.current_round_id round_id,
          s.question_id,s.status,s.grading_status,s.answer,s.result,s.revision,s.submission_generation,
          s.grading_generation,s.content_sha256,c.completion_status FROM practice_context c
        JOIN practice_collection p ON p.id=c.collection_id AND p.user_id=c.user_id AND p.deleted_at IS NULL
        JOIN practice_question_state s ON s.context_id=c.id AND s.user_id=c.user_id
        WHERE c.id=#{context} AND c.user_id=#{user} AND s.question_id=#{qid}
        """)
    Optional<Target> target(@Param("context") String context, @Param("user") String user, @Param("qid") String qid);

    @Update("""
        UPDATE practice_question_state s JOIN practice_context c ON c.id=s.context_id AND c.user_id=s.user_id
        JOIN practice_collection p ON p.id=c.collection_id AND p.user_id=c.user_id AND p.deleted_at IS NULL
        SET s.result=#{result},s.projection=#{projection},s.score=#{score},s.max_score=#{maxScore},
          s.grading_status=#{status},s.outline_states=#{outlineStates},s.grading_source='ai',
          s.revision=s.revision+1,s.updated_at=#{now}
        WHERE s.context_id=#{context} AND s.user_id=#{user} AND s.question_id=#{qid} AND s.status='submitted'
          AND s.submission_generation=#{submission} AND s.grading_generation=#{grading}
          AND s.revision=#{revision} AND c.current_round_id=#{round}
        """)
    int apply(@Param("context") String context, @Param("user") String user, @Param("qid") String qid,
            @Param("submission") int submission, @Param("grading") int grading, @Param("revision") long revision,
            @Param("round") String round, @Param("result") String result, @Param("projection") String projection,
            @Param("score") BigDecimal score, @Param("maxScore") BigDecimal maxScore, @Param("status") String status,
            @Param("outlineStates") String outlineStates, @Param("now") Instant now);
}
