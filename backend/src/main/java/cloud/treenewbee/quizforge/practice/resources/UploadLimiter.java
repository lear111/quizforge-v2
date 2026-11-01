package cloud.treenewbee.quizforge.practice.resources;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/** Single-app, per-account fixed window; replace with shared state before scaling app replicas. */
@Component
public class UploadLimiter {
    private record Window(Instant end, int count) {}
    private final Map<String, Window> users = new HashMap<>();
    private final Clock clock;
    private Instant sweep = Instant.MIN;
    public UploadLimiter(Clock clock) { this.clock = clock; }

    public synchronized void acquire(String userId) {
        Instant now = clock.instant();
        if (!now.isBefore(sweep)) {
            users.values().removeIf(window -> !now.isBefore(window.end()));
            sweep = now.plusSeconds(60);
        }
        Window previous = users.get(userId);
        if (previous == null || !now.isBefore(previous.end())) previous = new Window(now.plusSeconds(60), 0);
        if (previous.count() >= 30)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESOURCE_RATE_LIMIT", "图片上传过于频繁，请稍后再试");
        users.put(userId, new Window(previous.end(), previous.count() + 1));
    }
}
