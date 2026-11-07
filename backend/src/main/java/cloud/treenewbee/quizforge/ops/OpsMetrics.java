package cloud.treenewbee.quizforge.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 运维指标（docs/ops/01-deploy.md 第 7 节）：导入队列长度、最近失败的导入与 AI 任务。
 * 指标来自数据库：每次抓取最多查询一次（缓存 {@value #TTL_SECONDS} 秒），都走状态索引。数据库不可用时为 NaN，不抛错。
 */
@Component
public class OpsMetrics {

    private static final Logger log = LoggerFactory.getLogger(OpsMetrics.class);
    private static final int TTL_SECONDS = 30;

    private record Sample(double value, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Sample> cache = new ConcurrentHashMap<>();

    public OpsMetrics(MeterRegistry meters, JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        gauge(meters, "qf.import.queue.length", "queued", () -> count("SELECT COUNT(*) FROM content_import_job WHERE status = 'queued'"));
        gauge(meters, "qf.import.queue.length", "running", () -> count("SELECT COUNT(*) FROM content_import_job WHERE status = 'running'"));
        gauge(meters, "qf.import.jobs.failed.recent", null, () -> recent("content_import_job"));
        gauge(meters, "qf.ai.tasks.failed.recent", null, () -> recent("ai_grading_task"));
        gauge(meters, "qf.ai.tasks.queue.length", null, () -> count("SELECT COUNT(*) FROM ai_grading_task WHERE status = 'queued'"));
    }

    private void gauge(MeterRegistry meters, String name, String status, Supplier<Double> read) {
        String key = name + ":" + status;
        var builder = Gauge.builder(name, () -> cached(key, read));
        if (status != null) builder.tag("status", status);
        builder.register(meters);
    }

    private double cached(String key, Supplier<Double> read) {
        Instant now = clock.instant();
        Sample sample = cache.get(key);
        if (sample != null && sample.at().plusSeconds(TTL_SECONDS).isAfter(now)) return sample.value();
        double value = read.get();
        cache.put(key, new Sample(value, now));
        return value;
    }

    /** 最近 1 小时内失败的任务数。 */
    private double recent(String table) {
        return count("SELECT COUNT(*) FROM " + table + " WHERE status = 'failed' AND updated_at >= ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(1))));
    }

    private double count(String sql, Object... args) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class, args);
            return value == null ? Double.NaN : value;
        } catch (RuntimeException error) {
            log.warn("读取运维指标失败：{}", error.getMessage());
            return Double.NaN;
        }
    }
}
