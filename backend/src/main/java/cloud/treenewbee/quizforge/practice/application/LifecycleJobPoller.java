package cloud.treenewbee.quizforge.practice.application;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import cloud.treenewbee.quizforge.shared.jobs.DatabaseJobPoller;

/** 保留练习任务的调度入口，空闲退避与唤醒机制由持久化任务共用。 */
final class LifecycleJobPoller extends DatabaseJobPoller {
    LifecycleJobPoller(ScheduledExecutorService worker, BooleanSupplier tick, long interval) {
        super(worker, tick, interval);
    }
}
