package cloud.treenewbee.quizforge.practice.application;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 单次调度避免空轮询；代次校验拦住已取消但恰好开始执行的回调。 */
final class LifecycleJobPoller implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LifecycleJobPoller.class);
    private static final long MAX_IDLE_MS = 5000;

    private final ScheduledExecutorService worker;
    private final BooleanSupplier tick;
    private final long interval;
    private ScheduledFuture<?> scheduled;
    private long generation;
    private long idleDelay;
    private boolean started;
    private boolean running;
    private boolean wakeRequested;
    private boolean closed;

    LifecycleJobPoller(ScheduledExecutorService worker, BooleanSupplier tick, long interval) {
        this.worker = worker;
        this.tick = tick;
        this.interval = Math.max(10, Math.min(MAX_IDLE_MS, interval));
        this.idleDelay = this.interval;
    }

    synchronized void start() {
        if (!started && !closed) {
            started = true;
            schedule(100);
        }
    }

    synchronized void wake() {
        if (!started || closed) {
            return;
        }
        idleDelay = interval;
        if (running) {
            wakeRequested = true;
        } else {
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            schedule(0);
        }
    }

    private void schedule(long delay) {
        long version = ++generation;
        scheduled = worker.schedule(() -> poll(version), delay, TimeUnit.MILLISECONDS);
    }

    private void poll(long version) {
        synchronized (this) {
            if (closed || version != generation) {
                return;
            }
            scheduled = null;
            running = true;
        }
        boolean found = false;
        try {
            found = tick.getAsBoolean();
        } catch (Exception error) {
            LOG.warn("练习后台任务调度失败", error);
        } finally {
            synchronized (this) {
                running = false;
                if (!closed) {
                    idleDelay = found || wakeRequested ? interval : Math.min(MAX_IDLE_MS, idleDelay * 2);
                    schedule(wakeRequested ? 0 : idleDelay);
                    wakeRequested = false;
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        worker.shutdownNow();
    }
}
