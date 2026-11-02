package cloud.treenewbee.quizforge.practice.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 草稿进度是派生数据；不得覆盖后一次草稿或已提交的判分状态。 */
@Mapper
public interface DraftOutlineMapper {
    @Update("""
            UPDATE practice_question_state
            SET outline_states = #{outlineStates}, answer_state = #{answerState}
            WHERE context_id = #{contextId} AND user_id = #{userId}
              AND question_id = #{questionId} AND BINARY question_id = BINARY #{questionId}
              AND revision = #{revision} AND status = 'draft'
            """)
    int update(@Param("contextId") String contextId, @Param("userId") String userId,
            @Param("questionId") String questionId, @Param("revision") long revision,
            @Param("outlineStates") String outlineStates, @Param("answerState") String answerState);

}
