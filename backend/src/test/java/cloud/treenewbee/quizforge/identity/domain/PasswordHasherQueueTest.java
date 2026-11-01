package cloud.treenewbee.quizforge.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import cloud.treenewbee.quizforge.shared.web.ApiException;

/** 密码哈希超过并发数时排队等待（D-12），等待超时才返回 503。 */
class PasswordHasherQueueTest {

    @Test
    void defaultsAllowTwoConcurrentHashesAndWaitAboutTwoSeconds() {
        assertThat(PasswordHasher.PERMITS).isEqualTo(2);
        assertThat(PasswordHasher.MAX_WAIT).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void waitsForAPermitInsteadOfFailingImmediately() throws Exception {
        PasswordHasher hasher = new PasswordHasher(1, Duration.ofSeconds(10));
        String stored = hasher.hash("pass12345");
        hasher.permits().acquire();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            return hasher.verify("pass12345", stored);
        });
        started.await();
        // 名额被占用时请求在排队，没有立即失败。
        assertThat(hasher.permits().hasQueuedThreads() || !waiting.isDone()).isTrue();
        hasher.permits().release();
        assertThat(waiting.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void returnsBusyWhenNoPermitBecomesAvailableInTime() throws Exception {
        PasswordHasher hasher = new PasswordHasher(1, Duration.ofMillis(50));
        hasher.permits().acquire();
        try {
            assertThatThrownBy(() -> hasher.hash("pass12345"))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status().value()).isEqualTo(503);
                        assertThat(error.code()).isEqualTo("AUTH_BUSY");
                    });
        } finally {
            hasher.permits().release();
        }
        assertThat(hasher.permits().availablePermits()).isEqualTo(1);
    }
}
