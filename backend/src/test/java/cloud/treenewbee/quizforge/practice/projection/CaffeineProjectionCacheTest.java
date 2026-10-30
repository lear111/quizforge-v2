package cloud.treenewbee.quizforge.practice.projection;

import static cloud.treenewbee.quizforge.practice.projection.ProjectionCacheTestSupport.backing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(90)
class CaffeineProjectionCacheTest {
    @Test
    void hitReusesTheSameStringWithoutInvokingLoader() {
        var cache = new CaffeineProjectionCache();
        var key = new ProjectionKey("content", "extension");
        String projected = new String("{\"stem\":\"题目\"}");
        assertThat(cache.get(key, () -> projected)).isSameAs(projected);
        assertThat(cache.get(new ProjectionKey("content", "extension"), () -> {
            throw new AssertionError("A cache hit must not execute the loader");
        })).isSameAs(projected);
        assertThat(backing(cache).stats().hitCount()).isEqualTo(1);
        assertThat(backing(cache).stats().loadSuccessCount()).isEqualTo(1);
    }

    @Test
    void changesToEitherContentHashOrExtensionDigestLoadIndependently() {
        var cache = new CaffeineProjectionCache();
        var calls = new AtomicInteger();
        var keys = List.of(new ProjectionKey("content", "extension"),
                new ProjectionKey("other-content", "extension"), new ProjectionKey("content", "other-extension"));
        for (var key : keys) assertThat(cache.get(key, () -> "projection-" + calls.incrementAndGet()))
                .isEqualTo("projection-" + (keys.indexOf(key) + 1));
        for (var key : keys) assertThat(cache.get(key, () -> { throw new AssertionError("cached"); }))
                .isEqualTo("projection-" + (keys.indexOf(key) + 1));
        assertThat(calls).hasValue(3);
    }

    @Test
    void loaderExceptionIsPropagatedByIdentityAndFailureIsNotCached() {
        var cache = new CaffeineProjectionCache();
        var key = new ProjectionKey("content", "extension");
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("loader failure");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> cache.get(key, () -> { calls.incrementAndGet(); throw failure; })).isSameAs(failure);
            assertThat(backing(cache).asMap()).doesNotContainKey(key);
        }
        assertThat(cache.get(key, () -> { calls.incrementAndGet(); return "recovered"; })).isEqualTo("recovered");
        assertThat(cache.get(key, () -> { throw new AssertionError("cached recovery"); })).isEqualTo("recovered");
        assertThat(calls).hasValue(3);
        assertThat(backing(cache).stats().loadFailureCount()).isEqualTo(2);
        assertThat(backing(cache).stats().loadSuccessCount()).isEqualTo(1);
    }

    @Test
    void exceedingMaximumWeightEvictsAndTheEvictedKeyCanLoadAgain() {
        var cache = new CaffeineProjectionCache();
        var actual = backing(cache);
        var eviction = actual.policy().eviction().orElseThrow();
        assertThat(eviction.getMaximum()).isEqualTo(64L * 1024 * 1024);
        // Lower the policy on this instance; keep the production weigher and eviction algorithm.
        eviction.setMaximum(10);
        var first = new ProjectionKey("first", "extension");
        var second = new ProjectionKey("second", "extension");
        cache.get(first, () -> "aaaaaa");
        cache.get(second, () -> "bbbbbb");
        actual.cleanUp();
        assertThat(actual.estimatedSize()).isEqualTo(1);
        assertThat(eviction.weightedSize().orElseThrow()).isEqualTo(6);
        assertThat(actual.stats().evictionCount()).isGreaterThanOrEqualTo(1);
        assertThat(actual.stats().evictionWeight()).isGreaterThanOrEqualTo(6);
        var evicted = actual.asMap().containsKey(first) ? second : first;
        var calls = new AtomicInteger();
        assertThat(cache.get(evicted, () -> { calls.incrementAndGet(); return "cccccc"; })).isEqualTo("cccccc");
        assertThat(calls).hasValue(1);
        actual.cleanUp();
        assertThat(eviction.weightedSize().orElseThrow()).isLessThanOrEqualTo(10);
    }

    @Test
    void oneHundredThreadsWaitingOnTheSameMissingKeyRunExactlyOneLoader() throws Exception {
        var cache = new CaffeineProjectionCache();
        var key = new ProjectionKey("content", "extension");
        String projected = new String("{\"stem\":\"shared\"}");
        var calls = new AtomicInteger();
        var ready = new CountDownLatch(100);
        var start = new CountDownLatch(1);
        var loaderEntered = new CountDownLatch(1);
        var releaseLoader = new CountDownLatch(1);
        var threads = new ConcurrentLinkedQueue<Thread>();
        var pool = Executors.newFixedThreadPool(100);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) results.add(pool.submit(() -> {
                threads.add(Thread.currentThread()); ready.countDown(); waitFor(start);
                return cache.get(key, () -> {
                    calls.incrementAndGet(); loaderEntered.countDown(); waitFor(releaseLoader); return projected;
                });
            }));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(loaderEntered.await(30, TimeUnit.SECONDS)).isTrue();
            // A pre-call barrier alone does not prove callers have entered cache.get.
            // Observe all 100 inside the real implementation while the loader is held.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(threads).hasSize(100);
                for (Thread thread : threads) {
                    assertThat(thread.getState()).isIn(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING);
                    assertThat(List.of(thread.getStackTrace())).anyMatch(frame ->
                            frame.getClassName().equals(CaffeineProjectionCache.class.getName()) && frame.getMethodName().equals("get"));
                }
                assertThat(results).allMatch(result -> !result.isDone());
            });
            assertThat(calls).hasValue(1);
            releaseLoader.countDown();
            for (var result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isSameAs(projected);
            assertThat(calls).hasValue(1);
            assertThat(backing(cache).stats().loadSuccessCount()).isEqualTo(1);
        } finally {
            start.countDown(); releaseLoader.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aBlockedLoaderDoesNotBlockADifferentKey() throws Exception {
        var cache = new CaffeineProjectionCache();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> cache.get(new ProjectionKey("first", "extension"), () -> {
                entered.countDown(); waitFor(release); return "first";
            }));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            var independent = pool.submit(() -> cache.get(new ProjectionKey("second", "extension"), () -> "second"));
            assertThat(independent.get(10, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(blocked.isDone()).isFalse();
            release.countDown();
            assertThat(blocked.get(30, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(backing(cache).stats().loadSuccessCount()).isEqualTo(2);
        } finally {
            release.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(45, TimeUnit.SECONDS)) throw new AssertionError("Synchronization gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }
}
