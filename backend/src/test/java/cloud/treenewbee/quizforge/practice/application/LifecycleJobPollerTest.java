package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class LifecycleJobPollerTest {
    final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
    final List<Callback> callbacks = new ArrayList<>();
    final AtomicBoolean found = new AtomicBoolean();
    final AtomicInteger ticks = new AtomicInteger();
    LifecycleJobPoller poller;

    record Callback(Runnable run, long delay, ScheduledFuture<?> future) {}

    @BeforeEach
    void setup() {
        when(worker.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            var future = mock(ScheduledFuture.class);
            callbacks.add(new Callback(call.getArgument(0), call.getArgument(1), future));
            return future;
        });
        poller = new LifecycleJobPoller(worker, () -> {
            ticks.incrementAndGet();
            return found.get();
        }, 250);
    }

    @Test
    void emptyPollsBackOffToFiveSecondsAndWorkResetsTo250ms() {
        poller.start();
        assertThat(latest().delay()).isEqualTo(100);
        for (long delay : new long[] {500, 1000, 2000, 4000, 5000, 5000}) {
            latest().run().run();
            assertThat(latest().delay()).isEqualTo(delay);
        }
        found.set(true);
        latest().run().run();
        assertThat(latest().delay()).isEqualTo(250);
        latest().run().run();
        assertThat(latest().delay()).isEqualTo(250);
        found.set(false);
        latest().run().run();
        assertThat(latest().delay()).isEqualTo(500);
    }

    @Test
    void wakeCancelsIdleWaitAndStaleCallbackCannotExecuteOrScheduleAgain() {
        poller.start();
        for (int i = 0; i < 5; i++) {
            latest().run().run();
        }
        var cancelled = latest();
        int before = ticks.get();
        poller.wake();
        verify(cancelled.future()).cancel(false);
        assertThat(latest().delay()).isZero();
        int count = callbacks.size();
        cancelled.run().run();
        assertThat(ticks).hasValue(before);
        assertThat(callbacks).hasSize(count);
        found.set(true);
        latest().run().run();
        assertThat(ticks).hasValue(before + 1);
        assertThat(latest().delay()).isEqualTo(250);
    }

    @Test
    void wakeDuringTickRunsImmediatelyAfterItWithoutConcurrentTicks() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        poller = new LifecycleJobPoller(worker, () -> {
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
            return true;
        }, 250);
        poller.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(latest().run());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                poller.wake();
                poller.wake();
                assertThat(callbacks).hasSize(1);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(callbacks).hasSize(2);
        assertThat(latest().delay()).isZero();
        latest().run().run();
        assertThat(latest().delay()).isEqualTo(250);
    }

    @Test
    void errorStillSchedulesAnotherPoll() {
        poller = new LifecycleJobPoller(worker, () -> {
            throw new IllegalStateException("模拟数据库暂不可用");
        }, 250);
        poller.start();
        latest().run().run();
        assertThat(callbacks).hasSize(2);
        assertThat(latest().delay()).isEqualTo(500);
    }

    @Test
    void closeCancelsPendingTickAndWakeCannotRestartIt() {
        poller.start();
        var pending = latest();
        poller.close();
        poller.wake();
        poller.start();
        pending.run().run();
        verify(pending.future()).cancel(false);
        verify(worker).shutdownNow();
        assertThat(ticks).hasValue(0);
        assertThat(callbacks).hasSize(1);
    }

    @Test
    void repeatedStartAndWakeBeforeStartDoNotCreateExtraCallbacks() {
        poller.wake();
        assertThat(callbacks).isEmpty();
        poller.start();
        poller.start();
        assertThat(callbacks).hasSize(1);
    }

    private Callback latest() {
        return callbacks.getLast();
    }
}
