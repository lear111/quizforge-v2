package cloud.treenewbee.quizforge.practice.grading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BrowserGradingMapper {
    record Locked(String questionId, String answer, long revision, int submissionGeneration,
            int gradingGeneration, String gradingStatus, String gradingSource, byte[] browserReportSha256) {}

    @Select("""
            SELECT question_id,answer,revision,submission_generation,grading_generation,grading_status,
                   grading_source,browser_report_sha256
            FROM practice_question_state
            WHERE context_id=#{context} AND user_id=#{user} AND question_id=#{qid}
              AND BINARY question_id=BINARY #{qid} AND status='submitted'
            """)
    Optional<Locked> locked(@Param("context") String context, @Param("user") String user, @Param("qid") String qid);

    @Select("""
            SELECT question_id,answer,revision,submission_generation,grading_generation,grading_status,
                   grading_source,browser_report_sha256
            FROM practice_question_state WHERE context_id=#{context} AND user_id=#{user}
              AND status='submitted' AND grading_status='awaiting_browser'
            ORDER BY question_id LIMIT 20
            """)
    List<Locked> pending(@Param("context") String context, @Param("user") String user);

    @Select("""
            SELECT COUNT(*) FROM practice_question_state WHERE context_id=#{context} AND user_id=#{user}
              AND status='submitted' AND grading_status='awaiting_browser'
            """)
    int remaining(@Param("context") String context, @Param("user") String user);

    @Select("""
            SELECT COUNT(*) FROM practice_question_state WHERE context_id=#{context} AND user_id=#{user}
              AND ((status='submitted' AND grading_status='awaiting_browser') OR
                   (status='draft' AND answer IS NOT NULL AND JSON_TYPE(answer) != 'NULL'))
            """)
    int unfinished(@Param("context") String context, @Param("user") String user);

    @Update("""
            UPDATE practice_question_state SET grading_source='server'
            WHERE context_id=#{context} AND user_id=#{user} AND question_id=#{qid}
              AND status='submitted' AND revision=#{revision}
            """)
    int serverSource(@Param("context") String context, @Param("user") String user, @Param("qid") String qid,
            @Param("revision") long revision);

    @Update("""
            UPDATE practice_question_state SET status='submitted',grading_status='awaiting_browser',
                result=NULL,score=NULL,max_score=#{maxScore},outline_states=NULL,projection=JSON_OBJECT(),
                revision=revision+1,submission_generation=submission_generation+1,
                attempt_count=attempt_count+1,grading_source=NULL,browser_report_sha256=NULL,updated_at=#{now}
            WHERE context_id=#{context} AND question_id=#{qid} AND BINARY question_id=BINARY #{qid}
              AND user_id=#{user} AND status='draft' AND revision=#{revision}
            """)
    int lock(@Param("context") String context, @Param("user") String user, @Param("qid") String qid,
            @Param("revision") long revision, @Param("maxScore") BigDecimal maxScore, @Param("now") Instant now);

    @Update("""
            UPDATE practice_question_state s JOIN content_question q
              ON q.bank_id=#{bank} AND q.question_id=s.question_id
            SET s.status='submitted',s.grading_status='awaiting_browser',s.result=NULL,s.score=NULL,
                s.max_score=q.max_score,s.outline_states=NULL,s.projection=JSON_OBJECT(),s.revision=s.revision+1,
                s.submission_generation=s.submission_generation+1,s.attempt_count=s.attempt_count+1,
                s.grading_source=NULL,s.browser_report_sha256=NULL,s.updated_at=#{now}
            WHERE s.context_id=#{context} AND s.user_id=#{user} AND s.status='draft'
              AND s.answer IS NOT NULL AND JSON_TYPE(s.answer) != 'NULL'
            """)
    int lockBatch(@Param("context") String context, @Param("user") String user, @Param("bank") String bank,
            @Param("now") Instant now);

    @Update("""
            UPDATE practice_question_state SET result=#{result},projection=#{projected},score=#{score},
                max_score=#{maxScore},grading_status=#{status},outline_states=#{outlineStates},
                grading_source=#{source},browser_report_sha256=#{hash},revision=revision+1,
                first_correct=CASE WHEN attempt_count=1 AND #{status}='graded'
                    THEN #{score}=#{maxScore} ELSE first_correct END,updated_at=#{now}
            WHERE context_id=#{context} AND user_id=#{user} AND question_id=#{qid}
              AND BINARY question_id=BINARY #{qid} AND status='submitted'
              AND grading_status='awaiting_browser' AND submission_generation=#{generation}
              AND grading_generation=#{gradingGeneration}
            """)
    int apply(@Param("context") String context, @Param("user") String user, @Param("qid") String qid,
            @Param("generation") int generation, @Param("gradingGeneration") int gradingGeneration,
            @Param("result") String result, @Param("projected") String projected, @Param("score") BigDecimal score,
            @Param("maxScore") BigDecimal maxScore, @Param("status") String status,
            @Param("outlineStates") String outlineStates, @Param("source") String source,
            @Param("hash") byte[] hash, @Param("now") Instant now);
}
