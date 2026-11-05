package cloud.treenewbee.quizforge.ai.grading;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.practice.ai.PracticeAiAccess;
import cloud.treenewbee.quizforge.practice.application.GradingEvents;
import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AiGradingService {
    public record Ready() {}
    private final AiGradingMapper tasks;
    private final PracticeAiAccess practice;
    private final ReceiptMapper receipts;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public AiGradingService(AiGradingMapper tasks, PracticeAiAccess practice, ReceiptMapper receipts,
            TransactionTemplate tx, JsonMapper json, Clock clock, ApplicationEventPublisher events) {
        this.tasks = tasks; this.practice = practice; this.receipts = receipts;
        this.tx = tx; this.json = json; this.clock = clock; this.events = events;
    }

    public JsonNode grade(String user, String kind, String id, String qid, String request, String target, boolean force) {
        String normalized = GradingProtocol.target(target);
        return write(user, kind, id, qid, request, "grade\n" + normalized + "\n" + force, () -> {
            var view = practice.view(user, kind, id, qid);
            var existing = tasks.current(view.target().contextId(), qid, normalized, user).orElse(null);
            if (existing != null && Set.of("queued", "running", "model_returned").contains(existing.status()) && matches(existing, view))
                return dto(existing);
            return dto(create(view, normalized, false, null));
        });
    }

    public JsonNode current(String user, String kind, String id, String qid, String target) {
        var view = practice.view(user, kind, id, qid);
        var result = json.createObjectNode();
        result.set("task", tasks.current(view.target().contextId(), qid, GradingProtocol.target(target), user)
                .filter(task -> matches(task, view)).map(this::dto).orElse(json.nullNode()));
        return result;
    }

    public JsonNode task(String user, String kind, String id, String qid, String taskId) {
        practice.view(user, kind, id, qid);
        return dto(owned(user, kind, id, qid, taskId));
    }

    public JsonNode retry(String user, String kind, String id, String qid, String taskId, String request) {
        return write(user, kind, id, qid, request, "retry\n" + taskId, () -> {
            var old = owned(user, kind, id, qid, taskId);
            var view = practice.view(user, kind, id, qid);
            if (!matches(old, view)) throw stale();
            var active = tasks.current(view.target().contextId(), qid, old.targetId(), user).orElseThrow(AiGradingService::stale);
            if (Set.of("queued", "running", "model_returned").contains(active.status())) return dto(active);
            if (!active.id().equals(old.id())) throw stale();
            return dto(create(view, old.targetId(), false, null));
        });
    }

    /** 兼容旧客户端的确认接口：已自动采用时不重复 review。 */
    public JsonNode confirm(String user, String kind, String id, String qid, String taskId,
            String request, String version, Double score) {
        if (score != null && !Double.isFinite(score)) throw GradingProtocol.invalid("评分分数必须为有限数");
        var saved = owned(user, kind, id, qid, taskId);
        var replay = replay(user, request, hash(kind, id, qid, "confirm\n" + taskId + "\n" + version + "\n" + score));
        if (replay != null) return replay;
        var view = practice.view(user, kind, id, qid);
        if (!matches(saved, view) || !bound(saved)) throw stale();
        if (version != null && !version.equals(saved.candidateVersion())) throw candidateConflict();
        if (saved.candidate() == null || !Set.of("succeeded", "confirmed").contains(saved.status()))
            throw new ApiException(HttpStatus.CONFLICT, "AI_CANDIDATE_UNAVAILABLE", "这份 AI 建议已失效，不能确认");
        var candidate = (ObjectNode) json.readTree(saved.candidate());
        candidate.remove("version");
        double reviewScore = score == null ? candidate.path("score").asDouble() : score;
        var review = candidate.deepCopy();
        review.put("score", reviewScore);
        if (saved.status().equals("confirmed")) requireConfirmedScore(saved, view, reviewScore);
        var outcome = saved.status().equals("confirmed") ? null : practice.review(view, saved.targetId(), review);
        return write(user, kind, id, qid, request, "confirm\n" + taskId + "\n" + version + "\n" + score, () -> {
            var latest = owned(user, kind, id, qid, taskId);
            var current = practice.view(user, kind, id, qid);
            if (!matches(latest, current) || !bound(latest)) throw stale();
            if (!latest.status().equals("confirmed")) {
                var assessment = (ObjectNode) assessment(latest, candidate);
                assessment.put("reviewScore", reviewScore);
                if (outcome == null || current.target().revision() != view.target().revision() || !practice.apply(current, outcome, assessment)) throw stale();
                if (tasks.confirm(user, taskId, latest.candidateVersion(), clock.instant()) != 1) throw stale();
            } else requireConfirmedScore(latest, current, reviewScore);
            var result = (ObjectNode) dto(tasks.find(user, taskId).orElseThrow());
            result.set("payload", practice.payload(current));
            return result;
        });
    }

    @EventListener
    public void submitted(GradingEvents.Submitted event) {
        if (!tasks.autoGrade(event.userId())) return;
        practice.submitted(event.userId(), event.contextId(), event.questionId()).ifPresent(target -> {
            if (!practice.supportsAi(target)) return;
            var view = practice.view(event.userId(), PracticeAiAccess.kind(target), PracticeAiAccess.scope(target), event.questionId());
            create(view, "", true, null);
        });
    }

    @EventListener
    public void invalidated(GradingEvents.Invalidated event) {
        tasks.cancelQueued(event.contextId(), event.questionId(), event.userId(), clock.instant());
    }

    AiGradingTask create(PracticeAiAccess.View view, String target, boolean automatic, String input) {
        var t = view.target();
        var now = clock.instant();
        var task = new AiGradingTask(Ids.uuidV7(), t.userId(), PracticeAiAccess.kind(t), view.scope(), t.contextId(),
                t.questionId(), target, t.roundId(), t.submissionGeneration(), t.gradingGeneration(),
                PracticeAiAccess.hash(json.writeValueAsString(view.answer())), t.contentSha256(), "queued", automatic ? 10 : 0,
                automatic, 0, 0, false, now, null, input, null, null, null, 0, null, null, now, now);
        tasks.replaceBindings(t.contextId(), t.questionId(), t.userId(), target.isEmpty());
        if (tasks.insert(task) != 1) throw new IllegalStateException("评分任务未登记");
        tasks.bind(task);
        events.publishEvent(new Ready());
        return task;
    }

    boolean matches(AiGradingTask task, PracticeAiAccess.View view) {
        return practice.matches(view, task.roundId(), task.submissionGeneration(), task.gradingGeneration(), task.answerSha256(), task.contentSha256());
    }

    boolean bound(AiGradingTask task) {
        return tasks.current(task.contextId(), task.questionId(), task.targetId(), task.userId())
                .map(current -> current.id().equals(task.id())).orElse(false);
    }

    JsonNode assessment(AiGradingTask task, JsonNode candidate) {
        var assessment = (ObjectNode)candidate.deepCopy();
        assessment.put("taskId",task.id());
        assessment.set("reviewScore", candidate.path("score"));
        if (task.candidateVersion() != null) assessment.put("candidateVersion", task.candidateVersion());
        if (!task.targetId().isEmpty()) assessment.put("targetId",task.targetId());
        if (task.inputJson()!=null) assessment.set("maxScore",json.readTree(task.inputJson()).path("maxScore"));
        return assessment;
    }

    JsonNode dto(AiGradingTask task) {
        // 旧拓展仅轮询 queued/running；检查点阶段仍对页面显示为 running。
        var value = json.createObjectNode().put("taskId", task.id()).put("status", task.status().equals("model_returned") ? "running" : task.status()).put("cost", task.cost());
        if (!task.targetId().isEmpty()) value.put("targetId", task.targetId());
        if (task.candidate() != null) {
            var candidate = (ObjectNode) json.readTree(task.candidate());
            candidate.put("version", task.candidateVersion());
            value.set("candidate", candidate);
        }
        if (task.errorCode() != null) value.set("error", json.createObjectNode().put("code", task.errorCode()).put("message", task.errorMessage()));
        return json.readTree(json.writeValueAsString(value));
    }

    private void requireConfirmedScore(AiGradingTask task, PracticeAiAccess.View view, double score) {
        JsonNode result = view.state().path("result");
        JsonNode adopted = task.targetId().isEmpty() ? result.path("aiAssessment")
                : result.path("aiAssessments").path(task.targetId());
        if (!task.id().equals(adopted.path("taskId").asString())) throw stale();
        double saved = adopted.path("reviewScore").asDouble(adopted.path("score").asDouble());
        if (score != saved) throw candidateConflict();
    }

    private static ApiException candidateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "AI_CANDIDATE_CONFLICT", "这份 AI 评分已经保存，调整分数请使用人工评分");
    }

    private AiGradingTask owned(String user, String kind, String id, String qid, String task) {
        return tasks.find(user, task).filter(t -> t.kind().equals(kind) && t.collectionId().equals(id) && t.questionId().equals(qid))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "评分任务不存在"));
    }

    private JsonNode write(String user, String kind, String id, String qid, String request, String action, Supplier<JsonNode> work) {
        if (request == null || request.isBlank() || request.length() > 64) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求编号无效");
        byte[] hash = hash(kind, id, qid, action);
        JsonNode old = replay(user, request, hash);
        if (old != null) return old;
        var view = practice.view(user, kind, id, qid);
        try {
            return tx.execute(status -> {
                practice.lock(user, view.target().contextId());
                JsonNode prior = replay(user, request, hash);
                if (prior != null) return prior;
                JsonNode result = work.get();
                receipts.insert(user, request, hash, json.writeValueAsString(result));
                // MySQL JSON 会规范化数字与键序；首次响应也从已保存的回执读取。
                return replay(user, request, hash);
            });
        } catch (DuplicateKeyException | ApiException conflict) {
            var response = tx.execute(status -> replay(user, request, hash));
            if (response != null) return response;
            throw conflict;
        }
    }

    private JsonNode replay(String user, String request, byte[] hash) {
        var row = receipts.find(user, request);
        if (row == null) return null;
        if (!Arrays.equals(hash, row.requestSha256())) throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REUSED", "请求编号已用于不同操作");
        return json.readTree(row.response());
    }

    private byte[] hash(String kind, String id, String qid, String action) {
        return PracticeAiAccess.hash(json.writeValueAsString(java.util.List.of("ai", kind, id, qid, action)));
    }

    static ApiException stale() { return new ApiException(HttpStatus.CONFLICT, "AI_TASK_SUPERSEDED", "作答或评分已变化，请刷新后重试"); }
}
