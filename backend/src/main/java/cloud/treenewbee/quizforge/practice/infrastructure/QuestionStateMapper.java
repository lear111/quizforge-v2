package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuestionStateMapper {

    // 乐观锁更新：返回影响的行数（1 = 成功，0 = 冲突或不存在）
    @Update("""
            UPDATE practice_question_state
            SET answer = #{answer},
                revision = revision + 1,
                updated_at = #{updatedAt},
                status = 'draft'
            WHERE context_id = #{contextId}
              AND question_id = #{questionId}
              AND user_id = #{userId}
              AND revision = #{expectedRevision}
              AND status <> 'submitted'
            """)
    int updateDraft(@Param("contextId") String contextId,
                    @Param("questionId") String questionId,
                    @Param("userId") String userId,
                    @Param("expectedRevision") long expectedRevision,
                    @Param("answer") String answerJson,
                    @Param("updatedAt") Instant updatedAt);

        // 第一次保存：插入新行，revision = 1。
    // 用 INSERT … SELECT 在同一条 SQL 里检查存档归属：存档不属于该用户时插入 0 行（防 IDOR）。
    @Insert("""
            INSERT INTO practice_question_state (context_id, question_id, user_id, answer, revision, updated_at, status, content_sha256)
                SELECT #{contextId}, #{questionId}, #{userId}, #{answer}, 1, #{updatedAt}, 'draft', #{contentSha256}
                FROM practice_context
                WHERE id = #{contextId} AND user_id = #{userId}
            """)
    int insertDraft(@Param("contextId") String contextId,
                     @Param("questionId") String questionId,
                     @Param("userId") String userId,
                     @Param("answer") String answerJson,
                     @Param("updatedAt") Instant updatedAt, 
                     @Param("contentSha256") byte[] contentSha256);
}
