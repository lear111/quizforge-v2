package cloud.treenewbee.quizforge.rules;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java 调用 rule-runner 的唯一入口（rule-runner 协议第 3、7、8 节）。
 *
 * <p>
 * 三道防线，防止 rule-runner 变慢或故障时拖垮整个后端（级联故障）：
 * <ol>
 * <li><b>限流（舱壁）</b>：信号量限制同时进行的调用数，满了立即失败，不排队等待；</li>
 * <li><b>超时</b>：每次调用最多等待 deadline + 1 秒；</li>
 * <li><b>重试</b>：只在“连接失败”时重试一次。规则拒绝是确定结果，永不重试。</li>
 * </ol>
 */
@Component
public class RuleRunnerClient {

    /** Java 比 rule-runner 多等 1 秒，让 rule-runner 有机会自己返回 RULE_TIMEOUT（协议 7.2 节）。 */
    private static final Duration RESPONSE_GRACE = Duration.ofSeconds(1);

    private final RuleRunnerProperties properties;
    private final JsonMapper json;
    private final HttpClient http;
    private final Semaphore permits;
    private final MeterRegistry meters;

    public RuleRunnerClient(RuleRunnerProperties properties, JsonMapper json) {
        this(properties, json, new SimpleMeterRegistry());
    }

    @Autowired
    public RuleRunnerClient(RuleRunnerProperties properties, JsonMapper json, MeterRegistry meters) {
        this.meters = meters;
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.permits = new Semaphore(properties.maxConcurrency());
        Gauge.builder("qf.rule.runner.bulkhead.active", permits,
                p -> properties.maxConcurrency() - p.availablePermits()).register(meters);
        Gauge.builder("qf.rule.runner.bulkhead.limit", properties, RuleRunnerProperties::maxConcurrency).register(meters);
        meters.counter("qf.rule.runner.bulkhead.rejected");
    }

    /** 使用默认时限执行一个操作。 */
    public RuleResult execute(String op, RulePackage rulePackage, Map<String, ?> input) {
        return execute(op, rulePackage, input, properties.defaultDeadline());
    }

    /**
     * 执行一个规则操作。
     *
     * @return 成功或规则拒绝
     * @throws RuleRunnerException rule-runner 不可用（含本地并发已满）
     */
    public RuleResult execute(String op, RulePackage rulePackage, Map<String, ?> input, Duration deadline) {
        HttpRequest request = buildRequest(op, rulePackage, input, deadline);

        String operation = metricOp(op);
        Timer.Sample timing = Timer.start(meters);
        boolean acquired = permits.tryAcquire();
        try {
            if (!acquired) {
                meters.counter("qf.rule.runner.bulkhead.rejected").increment();
                throw new RuleRunnerException("rule-runner 并发已满");
            }
            RuleResult result;
            try {
                result = send(request);
            } catch (RuleRunnerException e) {
                if (e.getCause() instanceof ConnectException) {
                    result = send(request); // 连接失败，重试一次
                } else throw e;
            }
            if (!result.ok() && "RULE_TIMEOUT".equals(result.code())) {
                meters.counter("qf.rule.runner.timeouts", "op", operation, "source", "runner").increment();
            }
            return result;
        } catch (RuleRunnerException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                meters.counter("qf.rule.runner.timeouts", "op", operation, "source", "http").increment();
            }
            throw e;
        } finally {
            if (acquired) permits.release();
            timing.stop(Timer.builder("qf.rule.runner.duration").tag("op", operation).register(meters));
        }
    }

    private static String metricOp(String op) {
        if (op == null) return "other";
        return switch (op) {
            case "capabilities", "validateBank", "outlineBatch", "scoreBatch", "project", "projectBatch",
                    "validateAnswer", "submit", "review", "prepareAiGrading" -> op;
            default -> "other";
        };
    }

    /** 发送一次请求并解析响应；网络错误和非 200 状态都转换成 RuleRunnerException。 */
    private RuleResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parse(response);
        } catch (IOException e) {
            // 包括 ConnectException（连不上）和 HttpTimeoutException（等太久）
            throw new RuleRunnerException("rule-runner 调用失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuleRunnerException("rule-runner 调用被中断", e);
        }
    }

    /** 200 才是 rule-runner 给出的业务结果；其余状态码表示 rule-runner 自身出了问题（协议 3.2 节）。 */
    private RuleResult parse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new RuleRunnerException("rule-runner 返回 " + response.statusCode());
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (JacksonException e) {
            throw new RuleRunnerException("rule-runner 返回了无法解析的响应", e);
        }
        if (body.path("ok").asBoolean(false)) {
            return RuleResult.success(body.path("data"));
        }
        return RuleResult.rejected(body.path("code").asString("RULE_REJECTED"));
    }

    private HttpRequest buildRequest(String op, RulePackage rulePackage, Map<String, ?> input, Duration deadline) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", 1);
        body.put("requestId", Ids.uuidV7());
        body.put("op", op);
        body.put("deadlineMs", deadline.toMillis());
        body.put("package", rulePackage);
        body.put("input", input);
        return HttpRequest.newBuilder(properties.baseUrl().resolve("/v1/execute"))
                .timeout(deadline.plus(RESPONSE_GRACE))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", properties.token())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }
}
