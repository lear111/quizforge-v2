package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.quota.application.PracticeStorage;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.practice.infrastructure.QuestionStateMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/** 保存作答草稿：每题一行，用版本号做乐观锁，不同题目、不同用户的保存互不阻塞。 */
@Service
public class DraftService {

    private final QuestionStateMapper states;
    private final Clock clock;
    private final PracticeStorage storage;

    public DraftService(QuestionStateMapper states, Clock clock, PracticeStorage storage) {
        this.states = states;
        this.clock = clock;
        this.storage = storage;
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

        if (clientRevision == 0) {
            // 第一次保存：只 INSERT，不先 UPDATE（对不存在的行 UPDATE 会加间隙锁，并发时死锁）
            try {
                int inserted = states.insertDraft(contextId, questionId, userId, answerJson, now, contentSha256);
                if (inserted == 1) {
                    storage.state(userId, contextId, questionId);
                    return 1;
                }
                throw revisionConflict();   // 插入 0 行：存档不属于该用户（INSERT … SELECT 的归属检查）
            } catch (DuplicateKeyException e) {
                throw revisionConflict();   // 主键冲突：另一个请求先保存了这道题
            }
        }

        // 之后的保存：乐观锁更新
        if (states.updateDraft(contextId, questionId, userId, clientRevision, answerJson, now) == 1) {
            storage.state(userId, contextId, questionId);
            return clientRevision + 1;
        }
        throw revisionConflict();           // 版本号已过期，或这道题已提交
    }

    private static ApiException revisionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "内容已在别处修改，请刷新后重试");
    }
}
