package cloud.treenewbee.quizforge.activity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.activity.infrastructure.ActivityMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 在线活跃统计（参考旧平台 /api/activity：网页每 60 秒一次 heartbeat、每天首次可见一次 visit、
 * 练习切题时最多每分钟一次 practice）。
 *
 * <p>高频请求不写数据库：最近一次心跳时间只保存在内存中（“当前在线” = 5 分钟内有请求）；
 * 当天的活跃用户先记在内存，每分钟批量写入 activity_daily，关闭时再写一次。
 *
 * <p><strong>单实例限制</strong>：在线人数与尚未写入的当天活跃只存在于本进程。部署多个实例后，
 * 每个实例只看到自己收到的心跳，需要改为共享存储（例如 Redis）；重启会丢失在线状态与最多一分钟的
 * 未写入活跃记录（下一次心跳或访问即恢复）。
 */
@Service
public class ActivityTracker {

    public static final Set<String> KINDS = Set.of("visit", "practice", "heartbeat");
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final Duration ONLINE_WINDOW = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(ActivityTracker.class);

    private record DayUser(LocalDate day, String userId) {
    }

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<DayUser, Boolean> pending = new ConcurrentHashMap<>();
    private final ActivityMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "activity-flush");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${qf.activity.flush-enabled:true}") boolean enabled;
    @Value("${qf.activity.flush-interval-ms:60000}") long interval;

    public ActivityTracker(ActivityMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        if (enabled) worker.scheduleWithFixedDelay(this::safeFlush, interval, Math.max(1000, interval), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
        safeFlush();
    }

    /** 记一次活动；只操作内存。 */
    public void record(String userId, String kind) {
        if (!KINDS.contains(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "活动类型无效");
        Instant now = clock.instant();
        lastSeen.put(userId, now);
        if (!kind.equals("heartbeat")) {
            pending.merge(new DayUser(LocalDate.ofInstant(now, ZONE), userId), kind.equals("practice"), Boolean::logicalOr);
        }
    }

    /** 当前在线：5 分钟内有活动的用户数（本实例）。 */
    public long online() {
        Instant since = clock.instant().minus(ONLINE_WINDOW);
        return lastSeen.values().stream().filter(time -> time.isAfter(since)).count();
    }

    /** 把内存中的当天活跃批量写入数据库；失败时放回内存，下次再写。 */
    public synchronized int flush() {
        List<ActivityMapper.DailyEntry> entries = new ArrayList<>();
        for (DayUser key : List.copyOf(pending.keySet())) {
            Boolean practiced = pending.remove(key);
            if (practiced != null) entries.add(new ActivityMapper.DailyEntry(key.day(), key.userId(), practiced));
        }
        Instant stale = clock.instant().minus(ONLINE_WINDOW.multipliedBy(2));
        lastSeen.values().removeIf(time -> time.isBefore(stale));
        if (entries.isEmpty()) return 0;
        try {
            for (int from = 0; from < entries.size(); from += 500) {
                mapper.upsert(entries.subList(from, Math.min(entries.size(), from + 500)));
            }
            return entries.size();
        } catch (RuntimeException error) {
            for (var entry : entries) {
                pending.merge(new DayUser(entry.day(), entry.userId()), entry.practiced(), Boolean::logicalOr);
            }
            throw error;
        }
    }

    private void safeFlush() {
        try {
            flush();
        } catch (RuntimeException error) {
            log.warn("活跃统计写入失败，将在下一次重试", error);
        }
    }

    public List<ActivityMapper.DailyCount> daily(LocalDate from) {
        safeFlush();
        return mapper.daily(from);
    }
}
