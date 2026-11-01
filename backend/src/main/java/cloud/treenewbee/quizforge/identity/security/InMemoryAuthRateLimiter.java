package cloud.treenewbee.quizforge.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口：每个 IP 在任意 60 秒内最多 N 次（默认 12，与旧平台一致）。
 * 只保存窗口内的时间戳，每个 IP 最多 N 个；键过多时清理已过期的 IP。
 */
@Component
public class InMemoryAuthRateLimiter implements AuthRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    public InMemoryAuthRateLimiter(@Value("${qf.auth.rate-limit.requests-per-minute}") int limit, Clock clock) {
        if (limit < 1) throw new IllegalArgumentException("qf.auth.rate-limit.requests-per-minute must be positive");
        this.limit = limit;
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW);
        if (requests.size() > CLEANUP_THRESHOLD) cleanup(windowStart);
        Deque<Instant> window = requests.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(windowStart)) window.pollFirst();
            if (window.size() >= limit) {
                Duration wait = Duration.between(now, window.peekFirst().plus(WINDOW));
                long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
                return new Decision(false, seconds);
            }
            window.addLast(now);
            return new Decision(true, 0);
        }
    }

    private void cleanup(Instant windowStart) {
        requests.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Instant last = entry.getValue().peekLast();
                return last == null || !last.isAfter(windowStart);
            }
        });
    }
}
