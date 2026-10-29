package cloud.treenewbee.quizforge.practice.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.practice.infrastructure.QuestionStateMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;

// 这道题还没有作答行，第一次保存改为插入；主键冲突说明另一个请求刚好同时第一次保存
@Service
public class DraftService {

    private final QuestionStateMapper states;
    private final Clock clock;

    private static ApiException revisionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "内容已在别处修改，请刷新后重试");
    }

    public DraftService(QuestionStateMapper states, Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    /**
     * 保存作答草稿（乐观锁，数据库设计 5.1 节）。
     *
     * @param clientRevision 客户端读题时拿到的版本号；这道题还没有作答时为 0
     * @return 保存后的新版本号
     * @throws ApiException 409 REVISION_CONFLICT：版本号已过期，或这道题已提交
     */
    @Transactional
    public long saveDraft(String userId, String contextId, String questionId,
                          long clientRevision, String answerJson, byte[] contentSha256) {
        Instant now = clock.instant();

        int updated = states.updateDraft(contextId, questionId, userId, clientRevision, answerJson, now);
        if (updated == 1) {
            return clientRevision + 1;
        }

        if (clientRevision == 0) {
            try {
                states.insertDraft(contextId, questionId, userId, answerJson, now, contentSha256);
                return 1;
            } catch (DuplicateKeyException e) {
                throw revisionConflict();
            }
        }

        throw revisionConflict();
        
    }
}