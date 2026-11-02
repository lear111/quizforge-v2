package cloud.treenewbee.quizforge.ai.application;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型接入的配置（L5.2）。上游地址与模型 ID 由环境变量提供（全站一个上游，Key 池只放 Key 与单价）：
 * 它们决定 AI 辅导与评分代码能用的能力（图片、工具调用），随代码一起审查和部署；也避免后台账号被盗用时
 * 把 Key 发往任意地址。
 *
 * <p>超时与租约：一次调用最多尝试 {@link #MAX_KEY_ATTEMPTS} 个 Key，每个 Key 最长等待 callTimeout，
 * 所以同步调用最长约 callTimeout × 3。启动时检查 callTimeout × 3 + 10 秒 &lt; AI 任务租约（设计 08 第 2.4 节，
 * 默认 150 秒），否则启动失败，避免“慢但还活着”的评分任务被误判为崩溃。
 */
@Component
public class AiGatewaySettings {

    public static final int MAX_KEY_ATTEMPTS = 3;
    public static final int MAX_KEYS = 20;
    static final Duration LEASE_MARGIN = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String model;
    private final Duration callTimeout;
    private final Duration streamTimeout;
    private final int maxOutputTokens;
    private final int perUserConcurrency;
    private final int siteConcurrency;
    private final Duration taskLease;

    public AiGatewaySettings(@Value("${qf.ai.base-url}") String baseUrl,
                             @Value("${qf.ai.model}") String model,
                             @Value("${qf.ai.call-timeout:40s}") Duration callTimeout,
                             @Value("${qf.ai.stream-timeout:300s}") Duration streamTimeout,
                             @Value("${qf.ai.max-output-tokens:8192}") int maxOutputTokens,
                             @Value("${qf.ai.per-user-concurrency:2}") int perUserConcurrency,
                             @Value("${qf.ai.site-concurrency:8}") int siteConcurrency,
                             @Value("${qf.ai.task-lease:150s}") Duration taskLease) {
        this.baseUrl = baseUrl(baseUrl);
        if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,64}")) {
            throw new IllegalStateException("qf.ai.model must be a model id such as qwen-vl-plus");
        }
        this.model = model;
        if (callTimeout.compareTo(Duration.ofSeconds(1)) < 0 || streamTimeout.compareTo(callTimeout) < 0) {
            throw new IllegalStateException("qf.ai.call-timeout must be >= 1s and qf.ai.stream-timeout >= call-timeout");
        }
        if (callTimeout.multipliedBy(MAX_KEY_ATTEMPTS).plus(LEASE_MARGIN).compareTo(taskLease) >= 0) {
            throw new IllegalStateException("qf.ai.call-timeout × " + MAX_KEY_ATTEMPTS + " + " + LEASE_MARGIN.toSeconds()
                    + "s must be shorter than the AI task lease qf.ai.task-lease (" + taskLease.toSeconds() + "s)");
        }
        if (maxOutputTokens < 16 || maxOutputTokens > 65_536 || perUserConcurrency < 1 || perUserConcurrency > 8
                || siteConcurrency < 1 || siteConcurrency > 1000) {
            throw new IllegalStateException("Invalid qf.ai concurrency or max-output-tokens settings");
        }
        this.callTimeout = callTimeout;
        this.streamTimeout = streamTimeout;
        this.maxOutputTokens = maxOutputTokens;
        this.perUserConcurrency = perUserConcurrency;
        this.siteConcurrency = siteConcurrency;
        this.taskLease = taskLease;
    }

    /** 只接受 HTTPS；http 只允许本机地址（测试与本机假上游）。不带用户信息、查询与片段。 */
    static String baseUrl(String raw) {
        try {
            URI uri = URI.create(raw.strip());
            String host = uri.getHost();
            boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1"));
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || local && "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (RuntimeException error) {
            throw new IllegalStateException("qf.ai.base-url must be an https URL such as "
                    + "https://dashscope.aliyuncs.com/compatible-mode/v1");
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String host() {
        return URI.create(baseUrl).getAuthority();
    }

    public String model() {
        return model;
    }

    public Duration callTimeout() {
        return callTimeout;
    }

    public Duration streamTimeout() {
        return streamTimeout;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int perUserConcurrency() {
        return perUserConcurrency;
    }

    public int siteConcurrency() {
        return siteConcurrency;
    }

    public Duration taskLease() {
        return taskLease;
    }
}
