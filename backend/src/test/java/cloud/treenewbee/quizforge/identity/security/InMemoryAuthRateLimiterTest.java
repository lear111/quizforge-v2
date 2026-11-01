package cloud.treenewbee.quizforge.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class InMemoryAuthRateLimiterTest {

    private static final class SteppingClock extends Clock {
        Instant now = Instant.parse("2026-11-01T08:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void slidingWindowAllowsLimitThenReportsSecondsUntilOldestExpires() {
        SteppingClock clock = new SteppingClock();
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(3, clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        clock.now = clock.now.plusSeconds(10);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        var rejected = limiter.tryAcquire("a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(50);
        // 被拒绝的请求不计数；最早一次滑出窗口后恢复一个名额。
        clock.now = clock.now.plusSeconds(50);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void retryAfterIsAtLeastOneSecondAndLimitMustBePositive() {
        SteppingClock clock = new SteppingClock();
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(1, clock);
        limiter.tryAcquire("a");
        clock.now = clock.now.plus(Duration.ofMillis(59_999));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> new InMemoryAuthRateLimiter(0, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
