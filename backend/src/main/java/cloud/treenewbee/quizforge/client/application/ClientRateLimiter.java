package cloud.treenewbee.quizforge.client.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 客户端接口的限频：每分钟固定窗口，键为“分组 + 用户或 IP”（与旧平台 RequestLimit 相同）。
 * 单实例内存计数；多实例部署时换成共享存储，接口不变。
 */
@Component
public class ClientRateLimiter {

    /** 超限时由调用方决定错误格式（设备接口与固定版本导入的格式不同）。 */
    public interface Failure {
        RuntimeException of(HttpStatus status, String message);
    }

    private record Entry(long until, int count) {
    }

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public ClientRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized void check(String group, String key, int maximum, Failure failure) {
        long now = clock.millis();
        entries.values().removeIf(entry -> entry.until() <= now);
        String id = group + ':' + key;
        Entry entry = entries.get(id);
        if (entry == null) {
            if (entries.size() >= MAX_ENTRIES) throw failure.of(HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后再试");
            entry = new Entry(now + WINDOW_MILLIS, 0);
        }
        if (entry.count() >= maximum) throw failure.of(HttpStatus.TOO_MANY_REQUESTS, "操作太频繁，请稍后再试");
        entries.put(id, new Entry(entry.until(), entry.count() + 1));
    }
}
