package cloud.treenewbee.quizforge.ai.grading;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.ai.application.AiGateway;
import cloud.treenewbee.quizforge.ai.application.AiGatewaySettings;
import cloud.treenewbee.quizforge.practice.ai.PracticeAiAccess;
import cloud.treenewbee.quizforge.practice.application.GradeOutcome;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 由现有数据库轮询器驱动，模型调用复用网关执行器，租约期间不持有事务。 */
@Service
public class AiGradingJobs implements AiTaskQueue {
    private final AiGradingMapper tasks;
    private final AiGradingService service;
    private final PracticeAiAccess practice;
    private final GradingProtocol protocol;
    private final GradingPrompt prompt;
    private final AiGateway gateway;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final Semaphore slots;
    private final boolean enabled;
    private final Duration lease;

    public AiGradingJobs(AiGradingMapper tasks, AiGradingService service, PracticeAiAccess practice,
            GradingProtocol protocol, GradingPrompt prompt, AiGateway gateway, TransactionTemplate tx,
            JsonMapper json, Clock clock, AiGatewaySettings settings, @Value("${qf.ai.grading.concurrency:2}") int concurrency,
            @Value("${qf.ai.grading.jobs-enabled:true}") boolean enabled) {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("AI 评分并发必须为 1..8");
        this.tasks = tasks; this.service = service; this.practice = practice; this.protocol = protocol;
        this.prompt = prompt; this.gateway = gateway; this.tx = tx; this.json = json; this.clock = clock;
        this.slots = new Semaphore(concurrency); this.enabled = enabled; this.lease = settings.taskLease();
    }

    public boolean tick() {
        if (!enabled || !slots.tryAcquire()) return false;
        AiGradingTask task;
        try { task = claim().orElse(null); }
        catch (RuntimeException failed) { slots.release(); throw failed; }
        if (task == null) { slots.release(); return false; }
        try {
            gateway.runBackgroundTask(() -> {
                try { run(task); }
                finally { slots.release(); }
            });
        } catch (RuntimeException stopped) {
            slots.release();
            tasks.yield(task.id(), task.userId(), task.attempts(), clock.instant(), clock.instant());
        }
        return true;
    }

    @Override
    public Optional<AiGradingTask> claim() {
        return tx.execute(status -> {
            tasks.recover(clock.instant());
            var task = tasks.next(clock.instant()).orElse(null);
            if (task == null) return Optional.empty();
            if (tasks.claim(task.id(), task.userId(), task.attempts(), clock.instant().plus(lease), clock.instant()) != 1) throw new Lost();
            return tasks.find(task.userId(), task.id());
        });
    }

    /** 测试可直接领取后运行，门控检查点与接管，无需等待后台定时器。 */
    public void run(AiGradingTask task) {
        try {
            tx.executeWithoutResult(status -> fence(task));
            if (task.checkpoint() != null) {
                returned(task, json.readValue(task.checkpoint(), AiGateway.Returned.class));
                return;
            }
            var view = current(task);
            JsonNode input;
            if (task.inputJson() == null) {
                JsonNode prepared = practice.prepare(view, task.targetId(), task.automatic());
                if (task.automatic() && prepared != null && prepared.isObject() && prepared.has("targets")) {
                    plan(task, prepared);
                    return;
                }
                if (task.automatic() && (prepared == null || prepared.isNull())) {
                    terminal(task, "confirmed", null, null, null, null);
                    return;
                }
                input = protocol.input(prepared);
                if (tasks.input(task.id(), task.userId(), task.attempts(), json.writeValueAsString(input), clock.instant()) != 1) throw new Lost();
            } else input = protocol.input(json.readTree(task.inputJson()));
            String ref = "grading:" + task.id() + ":" + task.attempts();
            gateway.chat(task.userId(), prompt.request(task.userId(), input, task.repairUsed()), ref, new AiGateway.TaskCheckpoint() {
                @Override public void beforeCall() {
                    if (tasks.beforeCall(task.id(), task.userId(), task.attempts(), clock.instant()) != 1) {
                        var latest = tasks.find(task.userId(), task.id()).orElseThrow(Lost::new);
                        if (latest.modelCalls() >= 3) throw GradingProtocol.invalid("已达到三次模型调用上限");
                        throw new Lost();
                    }
                }
                @Override public void returned(AiGateway.Returned value) {
                    // 限制异常上游输出的检查点大小；用量完整保留，仍会结算。
                    String text = value.text();
                    var bounded = new AiGateway.Returned(text == null ? "" : text.substring(0, Math.min(text.length(), 128 * 1024)),
                            value.inputTokens(), value.outputTokens(), value.usageReported(), value.cost(), value.keyId(), value.ref());
                    if (tasks.checkpoint(task.id(), task.userId(), task.attempts(), json.writeValueAsString(bounded), clock.instant()) != 1) throw new Lost();
                }
            });
            // 继续使用持久化的值，确保与重启接管完全一致。
            returned(task, json.readValue(tasks.find(task.userId(), task.id()).orElseThrow().checkpoint(), AiGateway.Returned.class));
        } catch (Lost stale) {
            // 租约被接管，旧执行者不得写回状态。
        } catch (RuntimeException error) {
            failed(task, error);
        }
    }

    private void plan(AiGradingTask task, JsonNode plan) {
        if (plan.size() != 1 || !plan.path("targets").isArray() || plan.path("targets").size() > 256) throw GradingProtocol.invalid("自动评分计划无效");
        var ids = new HashSet<String>();
        var inputs = new java.util.LinkedHashMap<String, String>();
        for (JsonNode target : plan.path("targets")) {
            if (!target.isObject() || target.size() != 2 || !target.path("targetId").isString()) throw GradingProtocol.invalid("自动评分目标无效");
            String id = GradingProtocol.target(target.path("targetId").asString());
            if (!ids.add(id)) throw GradingProtocol.invalid("自动评分目标重复");
            inputs.put(id, json.writeValueAsString(protocol.input(target.get("input"))));
        }
        tx.executeWithoutResult(status -> {
            practice.lock(task.userId(), task.contextId());
            var view = current(task);
            fence(task);
            // 整份计划先验证；原子替换父绑定，兄弟小题各自有独立绑定。
            tasks.replaceBindings(task.contextId(), task.questionId(), task.userId(), true);
            for (var input : inputs.entrySet()) service.create(view, input.getKey(), true, input.getValue());
            finish(task, "confirmed", null, null, null, 0, null, null);
        });
    }

    private void returned(AiGradingTask task, AiGateway.Returned output) {
        JsonNode candidate;
        try { candidate = protocol.candidate(output.text()); }
        catch (ApiException malformed) {
            tx.executeWithoutResult(status -> {
                var latest = fence(task);
                gateway.settleReturned(task.userId(), output);
                long cost = Math.addExact(latest.cost(), output.cost());
                if (!latest.repairUsed() && latest.modelCalls() < 3) {
                    tasks.retry(task.id(), task.userId(), task.attempts(), true, clock.instant().plusSeconds(1),
                            "AI_OUTPUT_INVALID", "模型输出格式不正确，正在修复", cost, clock.instant());
                } else finish(task, "failed", null, null, json.writeValueAsString(output), cost, "AI_OUTPUT_INVALID", "模型输出格式不正确，请重试");
            });
            return;
        }
        PracticeAiAccess.View view;
        GradeOutcome outcome;
        try {
            view = current(task);
            outcome = practice.review(view, task.targetId(), candidate);
        } catch (ApiException rejected) {
            boolean stale = rejected.status().value() == 404 || rejected.status().value() == 409;
            terminal(task, stale ? "superseded" : "succeeded", candidate, output,
                    stale ? "AI_TASK_SUPERSEDED" : "AI_REVIEW_REJECTED",
                    stale ? "作答或评分已变化，本次任务不再采用" : "题型未采用候选评分，原评分已保留");
            return;
        }
        tx.executeWithoutResult(status -> {
            practice.lock(task.userId(), task.contextId());
            var latest = fence(task);
            var refreshed = current(task);
            if (refreshed.target().revision() != view.target().revision()) {
                tasks.yield(task.id(), task.userId(), task.attempts(), clock.instant(), clock.instant());
                return;
            }
            gateway.settleReturned(task.userId(), output);
            String version = Ids.uuidV7();
            var assessment = (tools.jackson.databind.node.ObjectNode) service.assessment(latest, candidate);
            assessment.put("candidateVersion", version);
            if (!practice.apply(refreshed, outcome, assessment)) throw new Lost();
            finish(task, "confirmed", candidate, version, json.writeValueAsString(output),
                    Math.addExact(latest.cost(), output.cost()), null, null);
        });
    }

    private PracticeAiAccess.View current(AiGradingTask task) {
        var view = practice.view(task.userId(), task.kind(), task.collectionId(), task.questionId());
        if (!service.matches(task, view) || !service.bound(task)) throw AiGradingService.stale();
        return view;
    }

    private AiGradingTask fence(AiGradingTask task) {
        var latest = tasks.lock(task.userId(), task.id()).orElseThrow(Lost::new);
        if (latest.attempts() != task.attempts() || !java.util.Set.of("running", "model_returned").contains(latest.status())
                || latest.lockedUntil() == null || !latest.lockedUntil().isAfter(clock.instant())) throw new Lost();
        return latest;
    }

    private void terminal(AiGradingTask task, String status, JsonNode candidate, AiGateway.Returned output, String code, String message) {
        tx.executeWithoutResult(transaction -> {
            var latest = fence(task);
            if (output != null) gateway.settleReturned(task.userId(), output);
            finish(task, status, candidate, candidate == null ? null : Ids.uuidV7(), output == null ? latest.checkpoint() : json.writeValueAsString(output),
                    latest.cost() + (output == null ? 0 : output.cost()), code, message);
        });
    }

    private void failed(AiGradingTask task, RuntimeException error) {
        try {
            if (error instanceof RuleRunnerClient.BackgroundBusy || error instanceof RuleRunnerException && task.attempts() < 3) {
                tasks.yield(task.id(), task.userId(), task.attempts(), clock.instant().plusSeconds(5), clock.instant());
                return;
            }
            var latest = tasks.find(task.userId(), task.id()).orElseThrow(Lost::new);
            if (latest.checkpoint() != null && latest.attempts() == task.attempts()) {
                var output = json.readValue(latest.checkpoint(), AiGateway.Returned.class);
                if (error instanceof ApiException api && (api.status().value() == 404 || api.status().value() == 409))
                    terminal(task, "superseded", null, output, "AI_TASK_SUPERSEDED", "作答或评分已变化，本次任务不再采用");
                else terminal(task, "failed", null, output, "AI_REVIEW_FAILED", "评分结果暂未采用，原评分已保留，请稍后重试");
                return;
            }
            String code = error instanceof ApiException api ? api.code() : "AI_GRADING_FAILED";
            boolean transientError = code.startsWith("AI_UPSTREAM") || code.equals("AI_BUSY") || code.equals("AI_SITE_BUSY") || code.equals("AI_UNAVAILABLE") || code.equals("AI_KEY_REJECTED");
            if (transientError && latest.modelCalls() < 3 && latest.attempts() < 3) {
                tasks.retry(task.id(), task.userId(), task.attempts(), latest.repairUsed(), clock.instant().plusSeconds(1L << Math.min(task.attempts(), 5)),
                        code, "AI 服务暂时不可用，正在重试", latest.cost(), clock.instant());
            } else terminal(task, code.equals("AI_TASK_SUPERSEDED") ? "superseded" : "failed", null, null, code,
                    code.equals("AI_BALANCE_EXHAUSTED") ? "余额不足，请充值" : "AI 评分未完成，请稍后重试");
        } catch (Lost ignored) {
            // 已接管的任务由新执行者继续。
        }
    }

    private void finish(AiGradingTask task, String status, JsonNode candidate, String version, String checkpoint, long cost, String code, String message) {
        if (tasks.finish(task.id(), task.userId(), task.attempts(), status, candidate == null ? null : json.writeValueAsString(candidate),
                version, checkpoint, cost, code, message, clock.instant()) != 1) throw new Lost();
    }

    private static class Lost extends RuntimeException {}
}
