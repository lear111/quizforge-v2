package cloud.treenewbee.quizforge.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.ai.infrastructure.AiBalanceMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 余额对账（设计 08 第 1.2 节第 6 条）：检查每个用户“余额 = Σdelta”，以及流水的 seq 与 balance_after 是否连续；
 * 不一致时记录告警日志。定时执行（默认每小时），也可用维护命令 {@code maintenance ai-reconcile} 手动执行。
 */
@Service
public class AiReconciler {

    private static final Logger log = LoggerFactory.getLogger(AiReconciler.class);

    public record Result(Instant checkedAt, List<AiBalanceMapper.Mismatch> balanceMismatches,
                         List<AiBalanceMapper.Gap> ledgerGaps) {
        public boolean consistent() {
            return balanceMismatches.isEmpty() && ledgerGaps.isEmpty();
        }
    }

    private final AiBalanceMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-reconcile");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${qf.ai.reconcile.enabled:true}") boolean enabled;
    @Value("${qf.ai.reconcile.interval-ms:3600000}") long interval;

    public AiReconciler(AiBalanceMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        if (enabled) worker.scheduleWithFixedDelay(this::safeRun, interval, Math.max(60_000, interval), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    public Result run() {
        var result = new Result(clock.instant(), mapper.balanceMismatches(), mapper.ledgerGaps());
        for (var mismatch : result.balanceMismatches()) {
            log.warn("AI 余额对账不一致：用户 {} 余额 {}，流水之和 {}", mismatch.userId(), mismatch.balance(), mismatch.ledgerSum());
        }
        for (var gap : result.ledgerGaps()) {
            log.warn("AI 流水不连续：用户 {} seq {}（上一笔 seq {}），balance_after {}，上一笔 {}，delta {}",
                    gap.userId(), gap.seq(), gap.previousSeq(), gap.balanceAfter(), gap.previousBalance(), gap.delta());
        }
        return result;
    }

    private void safeRun() {
        try {
            run();
        } catch (RuntimeException error) {
            log.warn("AI 余额对账执行失败", error);
        }
    }
}
