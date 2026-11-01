package cloud.treenewbee.quizforge.practice.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.math.RoundingMode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeRoundMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeRoundQuestionRow;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeRoundRow;
import cloud.treenewbee.quizforge.practice.infrastructure.QuestionStateMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptRow;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SubmitService {

    private final PracticeReadService reads;
    private final DraftService drafts;
    private final SubmitGrader grader;
    private final QuestionStateMapper states;
    private final PracticeContextMapper contexts;
    private final PracticeRoundMapper rounds;
    private final ReceiptMapper receipts;
    private final RoundSnapshots snapshots;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;

    // 构造器
    public SubmitService(PracticeReadService reads, DraftService drafts, SubmitGrader grader,
                        QuestionStateMapper states, PracticeContextMapper contexts, PracticeRoundMapper rounds,
                        ReceiptMapper receipts, RoundSnapshots snapshots, TransactionTemplate tx,
                        Clock clock, JsonMapper json) {
        this.reads = reads;
        this.drafts = drafts;
        this.grader = grader;
        this.states = states;
        this.contexts = contexts;
        this.rounds = rounds;
        this.receipts = receipts;
        this.snapshots = snapshots;
        this.tx = tx;
        this.clock = clock;
        this.json = json;
    }

    public String submit(String userId, String requestId, String collectionId, String questionId,
                         long clientRevision, JsonNode answer) {
        String answerJson = json.writeValueAsString(answer);
        byte[] hash = requestHash(collectionId, questionId, clientRevision, answerJson);  // 复制 IdempotentDraftService 里的写法，开头加上 "submit" 以免和草稿的哈希混淆

        try {
            // 0. 查回执
            ReceiptRow existing = receipts.find(userId, requestId);
            if (existing != null) return replay(existing, hash);

            // 1. 先保存答案：只查数据库，不调用 rule-runner，确保后面任何外部调用失败时答案都已保存
            var target = reads.resolveTarget(userId, collectionId, questionId);
            String contextId = target.contextId();
            long revision = drafts.saveDraft(userId, contextId, questionId, clientRevision, answerJson,
                    target.contentSha256());

            // 答案已保存，接下来才做可能失败的外部调用（生成投影、判分）
            var prepared = reads.prepareDraft(userId, collectionId, questionId);

            // 2. 事务外判分
            GradeOutcome outcome = grader.grade(userId, collectionId, questionId, answer);   // 内含 result、score、maxScore、outlineStates、projection

            // 3. 事务内写入
            return tx.execute(status -> {
                Instant now = clock.instant();
                var context = contexts.lockForUpdate(contextId, userId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "题库不存在"));

                int updated = states.submit(
                        outcome.resultJson(),
                        outcome.gradingStatus(),
                        scale4(outcome.score()),
                        scale4(outcome.maxScore()),
                        outcome.outlineStatesJson(),
                        outcome.projectedJson(),
                        now,
                        contextId,
                        questionId,
                        userId,
                        revision
                );   // 分数先用 scale4() 处理
                if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "答案已被修改或已提交");

                String roundId = context.currentRoundId();
                if (roundId == null) {
                    roundId = UUID.randomUUID().toString();
                    if (rounds.insertRound(new PracticeRoundRow(roundId, userId, collectionId, contextId, "practice", "in_progress",
                            snapshots.collection(userId, collectionId), null, now, now, null, null)) != 1) {
                        throw new IllegalStateException("创建轮次失败");
                    }
                    if (contexts.setCurrentRound(contextId, userId, roundId, now) != 1) {
                        throw new IllegalStateException("设置当前轮次失败");
                    }
                }

                // upsert：插入时返回 1，更新时返回 2，所以判断 < 1
                if (rounds.upsertRoundQuestion(new PracticeRoundQuestionRow(roundId, questionId, userId,
                        snapshots.question(prepared, answer, outcome), prepared.extensionDigest(), now)) < 1) {
                    throw new IllegalStateException("保存题目快照失败");
                }

                if (contexts.bumpProgressVersion(contextId, userId, now) != 1) {
                    throw new IllegalStateException("更新汇总版本失败");
                }

                String response = "{\"revision\":" + (revision + 1) + "}";
                receipts.insert(userId, requestId, hash, response);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return recheck(userId, requestId, hash, e);
        } catch (ApiException e) {
            if (e.status() != HttpStatus.CONFLICT) throw e;
            return recheck(userId, requestId, hash, e);
        }
    }

    /** 分数与满分用同一规则舍入到 4 位小数，保证"满分才算全对"的比较不受精度影响；待评分时为 null。 */
    private static BigDecimal scale4(BigDecimal value) {
        return value != null ? value.setScale(4, RoundingMode.HALF_UP) : null;   // 注意 null
    }

    // replay、recheck、requestHash：参考 IdempotentDraftService 自己写

    private String replay(ReceiptRow row, byte[] hash) {
        if (java.util.Arrays.equals(row.requestSha256(), hash)) {
            return row.response();
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REUSED", "请求 ID 已被重复使用");
        }
    }

    private String recheck(String userId, String requestId, byte[] hash, RuntimeException original) {
        return tx.execute(status -> {
            ReceiptRow existing = receipts.find(userId, requestId);
            if (existing == null)
                throw original;
            return replay(existing, hash);
        });
    }

    private static byte[] requestHash(String collectionId, String questionId, long clientRevision, String answerJson) {
        // 复制 IdempotentDraftService 的写法，开头加上 "submit" 以免和草稿的哈希混淆
        try {
            var md = MessageDigest.getInstance("SHA-256");
            String canonical = "submit\n" + collectionId + "\n" + questionId + "\n" + clientRevision + "\n" + answerJson;
            return md.digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
