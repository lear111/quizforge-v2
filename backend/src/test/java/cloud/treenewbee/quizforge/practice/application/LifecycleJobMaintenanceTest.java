package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.practice.infrastructure.LifecycleJobMapper;
import tools.jackson.databind.json.JsonMapper;

class LifecycleJobMaintenanceTest {
    final LifecycleJobMapper mapper = mock(LifecycleJobMapper.class);
    final MutableClock clock = new MutableClock();

    @Test
    void cleanupIsBoundedOutsideClaimTransactionAndUsesConfigurableRetentionAndInterval() {
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        when(mapper.next(any())).thenReturn(Optional.empty());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return 3;
        }).when(mapper).deleteExpired(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        var jobs = new PracticeLifecycleJobs(mapper, null, null, null, null, null, null, null, null, null, null,
                tx, JsonMapper.builder().build(), clock);
        jobs.retention = Duration.ofDays(2);
        jobs.cleanupInterval = Duration.ofMinutes(15);
        jobs.cleanupBatchSize = 3;
        assertThat(jobs.tick()).isFalse();
        Instant cutoff = clock.instant().minus(Duration.ofDays(2));
        verify(mapper).deleteExpired("completed", cutoff, 3);
        verify(mapper).deleteExpired("failed", cutoff, 3);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        clearInvocations(mapper);
        clock.now = clock.now.plus(Duration.ofMinutes(15)).minusMillis(1);
        jobs.tick();
        verify(mapper, times(1)).next(clock.instant());
        // 不到清理间隔，只执行领取，不额外追删剩余批次。
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        clearInvocations(mapper);
        clock.now = clock.now.plusMillis(1);
        jobs.tick();
        verify(mapper).deleteExpired("completed", clock.instant().minus(Duration.ofDays(2)), 3);
        verify(mapper).deleteExpired("failed", clock.instant().minus(Duration.ofDays(2)), 3);
    }

    @Test
    void disabledSchedulerDoesNotPollOrCreateThreads() {
        var jobs = new PracticeLifecycleJobs(mapper, null, null, null, null, null, null, null, null, null, null,
                null, JsonMapper.builder().build(), clock);
        jobs.start();
        assertThat(jobs.poller).isNull();
        jobs.stop();
        verifyNoInteractions(mapper);
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
