package cloud.treenewbee.quizforge.rules;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Timeout(30)
class RuleRunnerMetricsTest {
    private static final RulePackage PACKAGE = new RulePackage("a".repeat(64), new RulePackage.ApiVersion(1, 5),
            false, true, new RulePackage.FileRef("b".repeat(64)), new RulePackage.FileRef("c".repeat(64)),
            new RulePackage.FileRef("d".repeat(64)));
    @Test void occupancyRejectionAndTimingTrackLogicalCallsWithoutLeakingPermits() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var peer = new FakeRuleRunner(); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = new RuleRunnerClient(new RuleRunnerProperties(peer.uri(), "test", 1, Duration.ofSeconds(10)), JsonMapper.builder().build(), meters);
            var gate = peer.block(1);
            var call = threads.submit(() -> client.execute("project", PACKAGE, Map.of()));
            try {
                gate.awaitEntered();
                assertThat(meters.get("qf.rule.runner.bulkhead.active").gauge().value()).isEqualTo(1);
                assertThatThrownBy(() -> client.execute("submit", PACKAGE, Map.of())).isInstanceOf(RuleRunnerException.class);
                assertThat(meters.get("qf.rule.runner.bulkhead.rejected").counter().count()).isEqualTo(1);
                assertThat(meters.get("qf.rule.runner.bulkhead.active").gauge().value()).isEqualTo(1);
            } finally { gate.close(); }
            assertThat(call.get().ok()).isTrue();
            assertThat(meters.get("qf.rule.runner.bulkhead.active").gauge().value()).isZero();
            assertThat(meters.get("qf.rule.runner.bulkhead.limit").gauge().value()).isEqualTo(1);
            assertThat(meters.get("qf.rule.runner.duration").tag("op", "project").timer().count()).isEqualTo(1);
            assertThat(meters.get("qf.rule.runner.duration").tag("op", "submit").timer().count()).isEqualTo(1);
        }
    }
    @Test void runnerAndHttpTimeoutsHaveSeparateSourcesAndAreCountedOnce() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var peer = new FakeRuleRunner()) {
            var client = new RuleRunnerClient(new RuleRunnerProperties(peer.uri(), "test", 1, Duration.ofMillis(1)), JsonMapper.builder().build(), meters);
            peer.respond(200, "{\"ok\":false,\"code\":\"RULE_TIMEOUT\"}");
            assertThat(client.execute("submit", PACKAGE, Map.of(), Duration.ofSeconds(10)).code()).isEqualTo("RULE_TIMEOUT");
            assertThat(meters.get("qf.rule.runner.timeouts").tags("op","submit","source","runner").counter().count()).isEqualTo(1);
            try (var gate = peer.block(1)) {
                assertThatThrownBy(() -> client.execute("project", PACKAGE, Map.of())).isInstanceOf(RuleRunnerException.class);
                assertThat(meters.get("qf.rule.runner.timeouts").tags("op","project","source","http").counter().count()).isEqualTo(1);
            }
            assertThat(peer.requests()).hasSize(2);
            assertThat(meters.get("qf.rule.runner.bulkhead.active").gauge().value()).isZero();
        }
    }
}
