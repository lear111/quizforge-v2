package cloud.treenewbee.quizforge.practice.application;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Context-serialized lifecycle writes; extension execution never holds a database transaction. */
@Service
public class PracticeLifecycleService {
    private final PracticeReadService reads;
    private final PracticeReadMapper readMapper;
    private final PracticeLifecycleMapper mapper;
    private final PracticeContextMapper contexts;
    private final PracticeSummaryService summaries;
    private final ReviewGrader grader;
    private final ReceiptMapper receipts;
    private final TransactionTemplate tx;
    private final QuestionStamps stamps;
    private final Clock clock;
    private final JsonMapper json;

    public PracticeLifecycleService(PracticeReadService reads, PracticeReadMapper readMapper, PracticeLifecycleMapper mapper,
            PracticeContextMapper contexts, PracticeSummaryService summaries, ReviewGrader grader, ReceiptMapper receipts,
            TransactionTemplate tx, QuestionStamps stamps, Clock clock, JsonMapper json) {
        this.reads = reads; this.readMapper = readMapper; this.mapper = mapper; this.contexts = contexts;
        this.summaries = summaries; this.grader = grader; this.receipts = receipts; this.tx = tx;
        this.stamps = stamps; this.clock = clock; this.json = json;
    }

    public JsonNode retry(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode data, String contentVersion) {
        var target = reads.resolveTarget(userId, collectionId, qid);
        reads.validateContentVersion(userId, collectionId, qid, contentVersion);
        byte[] hash = hash("retry", collectionId, qid, revision, data, contentVersion);
        var replay = receipt(userId, requestId, hash);
        if (replay != null) return replay;
        return prepareOrReplay(userId, requestId, hash, () -> {
            requireSubmitted(target.contextId(), qid, userId, revision);
            var current = mapper.context(target.contextId(), userId).orElseThrow();
            requireRetryable(round(current, userId));
            var prepared = reads.prepareDraft(userId, collectionId, qid);
            return execute(userId, requestId, hash, target.contextId(), qid, context -> {
                requireSubmitted(context.id(), qid, userId, revision);
                var round = round(context, userId);
                requireRetryable(round);
                if (mapper.retry(context.id(), qid, userId, revision, clock.instant()) != 1) throw revisionConflict();
                changed(context, userId);
                // No round-question write: retry must leave the existing history payload intact.
                return response(prepared.template(), prepared, null, null, revision + 1, "unanswered");
            });
        });
    }

    public JsonNode review(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode data, String contentVersion) {
        var target = reads.resolveTarget(userId, collectionId, qid);
        reads.validateContentVersion(userId, collectionId, qid, contentVersion);
        byte[] hash = hash("review", collectionId, qid, revision, data, contentVersion);
        var replay = receipt(userId, requestId, hash);
        if (replay != null) return replay;
        return prepareOrReplay(userId, requestId, hash, () -> {
            var current = requireSubmitted(target.contextId(), qid, userId, revision);
            var template = (PracticeViews.QuestionResponse) reads.question(userId, collectionId, qid, null);
            if (!template.capabilities().canReview()) throw conflict("REVIEW_UNAVAILABLE", "该题不支持人工评分");
            JsonNode answer = current.answer() == null ? json.nullNode() : json.readTree(current.answer());
            var state = (ObjectNode) json.valueToTree(template.state()); state.put("submitted", true);
            var outcome = grader.grade(userId, collectionId, qid, answer, state, data.get("review"));
            var prepared = new PracticeReadService.PreparedDraft(target.contextId(), target.contentSha256(),
                    target.extensionDigest(), template, userId);
            return execute(userId, requestId, hash, target.contextId(), qid, context -> {
                requireSubmitted(context.id(), qid, userId, revision);
                var round = round(context, userId);
                var oldPayload = mapper.payload(round.id(), qid, userId)
                        .orElseThrow(() -> conflict("REVIEW_UNAVAILABLE", "本轮没有该题的提交快照"));
                var result = json.readTree(outcome.resultJson());
                if (!(result instanceof ObjectNode object)) throw new IllegalStateException("人工评分结果必须为对象");
                object.put("gradingSource", "manual");
                var stored = new GradeOutcome(json.writeValueAsString(result), outcome.projectedJson(),
                        outcome.score() == null ? null : outcome.score().setScale(4, RoundingMode.HALF_UP),
                        outcome.maxScore().setScale(4, RoundingMode.HALF_UP), outcome.gradingStatus(), outcome.outlineStatesJson());
                if (mapper.review(context.id(), qid, userId, revision, stored.resultJson(), stored.score(), stored.maxScore(),
                        stored.gradingStatus(), stored.outlineStatesJson(), stored.projectedJson(), clock.instant()) != 1) throw revisionConflict();
                var response = response(template, prepared, answer, stored, revision + 1, "submitted");
                var frozen = (ObjectNode) json.readTree(oldPayload);
                ((ObjectNode) frozen.get("question")).set("data", response.path("question").get("data"));
                frozen.set("state", response.get("state")); frozen.set("stamp", response.get("stamp"));
                if (mapper.updatePayload(round.id(), qid, userId, json.writeValueAsString(frozen), clock.instant()) != 1)
                    throw new IllegalStateException("更新题目快照失败");
                changed(context, userId);
                if ("completed".equals(round.status())) {
                    var collection = reads.resolveCollection(userId, collectionId);
                    var summary = summaries.current(userId, collection.bankId(),
                            mapper.context(context.id(), userId).orElseThrow());
                    if (mapper.updateCompletedSummary(round.id(), userId, json.writeValueAsString(summary), clock.instant()) != 1)
                        throw new IllegalStateException("更新历史汇总失败");
                }
                return response;
            });
        });
    }

    public JsonNode restart(String userId, String collectionId, String requestId, String roundId, String summaryVersion) {
        var target = reads.resolveCollection(userId, collectionId);
        var input = json.createObjectNode(); input.put("roundId", roundId); input.put("summaryVersion", summaryVersion);
        byte[] hash = hash("restart", collectionId, "", 0, input, null);
        var replay = receipt(userId, requestId, hash);
        if (replay != null) return replay;
        return execute(userId, requestId, hash, target.contextId(), null, context -> {
            var summary = summaries.current(userId, target.bankId(), context);
            if (!java.util.Objects.equals(roundId, context.currentRoundId())
                    || !summaryVersion.equals(summary.path("summaryVersion").asString()))
                throw conflict("SUMMARY_CONFLICT", "练习数据已更新，请刷新汇总后再开始新一轮");
            mapper.reset(context.id(), userId, clock.instant());
            mapper.deleteWhiteboards(context.id(), userId);
            if (context.currentRoundId() != null) {
                var round = round(context, userId);
                if ("in_progress".equals(round.status())) {
                    summary.put("finished", true); summary.put("status", "completed"); summary.put("historyId", round.id());
                    if (mapper.complete(round.id(), userId, json.writeValueAsString(summary), clock.instant()) != 1)
                        throw new IllegalStateException("完成轮次失败");
                }
            }
            if (contexts.setCurrentRound(context.id(), userId, null, clock.instant()) != 1)
                throw new IllegalStateException("重置当前轮次失败");
            changed(context, userId);
            return json.createObjectNode().put("restarted", true);
        });
    }

    private PracticeReadRows.State requireSubmitted(String context, String qid, String userId, long revision) {
        var state = readMapper.state(context, qid, userId).orElseThrow(PracticeLifecycleService::revisionConflict);
        if (state.revision() != revision || !"submitted".equals(state.status())) throw revisionConflict();
        return state;
    }

    private PracticeRoundRow round(PracticeContextRow context, String userId) {
        if (context.currentRoundId() == null) throw conflict("REVIEW_UNAVAILABLE", "本轮没有已提交的题目");
        return mapper.round(context.currentRoundId(), context.id(), userId)
                .orElseThrow(() -> conflict("REVIEW_UNAVAILABLE", "本轮练习不存在"));
    }

    private static void requireRetryable(PracticeRoundRow round) {
        if (!"in_progress".equals(round.status()))
            throw conflict("PRACTICE_COMPLETED", "本轮练习已完成，不能重做本题，请再练一轮");
    }

    private void changed(PracticeContextRow context, String userId) {
        if (contexts.bumpProgressVersion(context.id(), userId, clock.instant()) != 1)
            throw new IllegalStateException("更新汇总版本失败");
    }

    private ObjectNode response(PracticeViews.QuestionResponse template, PracticeReadService.PreparedDraft prepared,
            JsonNode answer, GradeOutcome outcome, long revision, String status) {
        var response = (ObjectNode) json.valueToTree(template);
        if (outcome != null) ((ObjectNode) response.get("question")).set("data", json.readTree(outcome.projectedJson()));
        response.set("state", json.valueToTree(new PracticeViews.State(status, answer,
                outcome == null ? null : SubmissionResults.result(json, outcome.resultJson(), outcome.score(), outcome.maxScore(), outcome.gradingStatus()),
                revision, null, outcome == null || outcome.outlineStatesJson() == null ? null : json.readTree(outcome.outlineStatesJson()))));
        response.set("stamp", json.valueToTree(stamps.stamp(prepared.contextId(), template.question().id(), revision,
                prepared.contentSha256(), prepared.extensionDigest())));
        response.put("revision", revision);
        return response;
    }

    private JsonNode execute(String userId, String requestId, byte[] hash, String contextId, String qid,
            java.util.function.Function<PracticeContextRow, JsonNode> operation) {
        try {
            return tx.execute(status -> {
                // Lock first, before establishing an RR snapshot. A concurrent request may
                // have committed its receipt while this request waited for the context.
                var context = contexts.lockForUpdate(contextId, userId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "题库不存在"));
                // Draft updates only lock their own state. Acquire those locks before the
                // first consistent read, so restart validates the latest saved drafts too.
                mapper.lockStates(contextId, userId, qid);
                var replay = receipt(userId, requestId, hash);
                if (replay != null) return replay;
                var response = operation.apply(context);
                receipts.insert(userId, requestId, hash, json.writeValueAsString(response));
                // MySQL's JSON column normalizes object key order. Return that stored form
                // on the first request too, so HTTP replay is identical byte for byte.
                return receipt(userId, requestId, hash);
            });
        } catch (DuplicateKeyException error) {
            return recheck(userId, requestId, hash, error);
        } catch (ApiException error) {
            if (error.status() != HttpStatus.CONFLICT) throw error;
            return recheck(userId, requestId, hash, error);
        }
    }

    private JsonNode recheck(String userId, String requestId, byte[] hash, RuntimeException original) {
        return tx.execute(status -> {
            var replay = receipt(userId, requestId, hash);
            if (replay == null) throw original;
            return replay;
        });
    }

    private JsonNode prepareOrReplay(String userId, String requestId, byte[] hash, Supplier<JsonNode> prepare) {
        try { return prepare.get(); }
        catch (ApiException error) {
            if (error.status() != HttpStatus.CONFLICT) throw error;
            return recheck(userId, requestId, hash, error);
        }
    }

    private JsonNode receipt(String userId, String requestId, byte[] hash) {
        var row = receipts.find(userId, requestId);
        if (row == null) return null;
        if (!Arrays.equals(hash, row.requestSha256()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REUSED", "请求 ID 已被重复使用");
        return json.readTree(row.response());
    }

    private byte[] hash(String action, String collection, String qid, long revision, JsonNode data, String version) {
        try {
            var value = json.createObjectNode();
            value.put("action", action); value.put("collectionId", collection); value.put("questionId", qid);
            value.put("revision", revision); value.set("data", data); value.put("contentVersion", version);
            return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ApiException revisionConflict() { return conflict("REVISION_CONFLICT", "内容已在别处修改或状态不允许，请刷新后重试"); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
