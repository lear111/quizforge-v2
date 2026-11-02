package cloud.treenewbee.quizforge.ai.application;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * AI 调用的并发上限：每用户 {@code qf.ai.per-user-concurrency}（2），全站 {@code qf.ai.site-concurrency}。
 * 超出立即拒绝，不排队。单实例内存实现；多实例部署时换成 Redis 等共享计数，接口不变。
 *
 * <p>设计 08 第 1.2 节的透支上限 ≈ 每用户并发数 × 单次最高费用（由 max-output-tokens 决定）：
 * 余额检查与扣费之间，同一用户最多只有这么多个调用在进行。
 */
@Component
public class AiConcurrencyLimiter {

    /** 一个已占用的名额；调用结束（包括流式读完上游）时关闭，可重复关闭。 */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private final int perUser;
    private final int site;
    private final Map<String, Integer> active = new HashMap<>();
    private int total;

    public AiConcurrencyLimiter(AiGatewaySettings settings) {
        this.perUser = settings.perUserConcurrency();
        this.site = settings.siteConcurrency();
    }

    public synchronized Permit acquire(String userId) {
        int mine = active.getOrDefault(userId, 0);
        if (mine >= perUser) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "同时进行的 AI 请求太多，请等前面的完成后再试");
        }
        if (total >= site) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_SITE_BUSY", "AI 服务当前使用人数较多，请稍后重试");
        }
        active.put(userId, mine + 1);
        total++;
        return new Permit() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (AiConcurrencyLimiter.this) {
                    if (closed) return;
                    closed = true;
                    total--;
                    active.merge(userId, -1, Integer::sum);
                    if (active.get(userId) <= 0) active.remove(userId);
                }
            }
        };
    }

    synchronized int active(String userId) {
        return active.getOrDefault(userId, 0);
    }
}
