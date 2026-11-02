package cloud.treenewbee.quizforge.exam;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 有界索引扫描只负责触发交卷；真正的判分仍由练习任务池执行。 */
@Component
public class ExamDeadlineScanner {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExamDeadlineScanner.class);
    private final ExamMapper mapper;
    private final ExamService exams;
    private final Clock clock;
    private final boolean enabled;
    private final long interval;
    private ScheduledExecutorService worker;

    public ExamDeadlineScanner(ExamMapper mapper, ExamService exams, Clock clock,
        @Value("${qf.exam.scan-enabled:${qf.practice.jobs.enabled:true}}") boolean enabled,
        @Value("${qf.exam.scan-interval-ms:5000}") long interval) {
        this.mapper = mapper;
        this.exams = exams;
        this.clock = clock;
        this.enabled = enabled;
        this.interval = Math.max(100, interval);
    }

    @PostConstruct
    void start() {
        if (!enabled) return;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "exam-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> {
            try {
                scan();
            } catch (Exception error) {
                LOG.warn("扫描到期考试失败，将在下一轮重试");
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void scan() {
        for (var row:mapper.due(clock.instant(), 100)) {
            try {
                exams.submit(row.userId(), row.id(), null);
            } catch (Exception error) {
                LOG.warn("考试 {} 自动交卷未完成，将在下次扫描重试", row.id());
            }
        }
    }

    @PreDestroy void close() {
        if (worker != null)worker.shutdownNow();
    }
}
