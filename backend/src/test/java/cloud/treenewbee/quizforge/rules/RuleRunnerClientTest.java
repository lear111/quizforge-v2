package cloud.treenewbee.quizforge.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

@Timeout(30)
class RuleRunnerClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "test-internal-token";
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final RulePackage PACKAGE = new RulePackage("a".repeat(64), new RulePackage.ApiVersion(1, 5),
            false, true, new RulePackage.FileRef("b".repeat(64)), new RulePackage.FileRef("c".repeat(64)),
            new RulePackage.FileRef("d".repeat(64)));
    private static final Map<String, ?> INPUT = Map.of("data", Map.of("text", "题目"), "answer", List.of("A", "B"));

    @Test
    void requestMatchesProtocolIncludingNestedPackageAndDefaultDeadline() throws Exception {
        try (var peer = new FakeRuleRunner()) {
            var client = client(peer.uri(), 2);
            execute(client);
            var request = peer.requests().getFirst();
            assertThat(request.uri().toString()).isEqualTo("/v1/execute");
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(request.headers().getFirst("X-Internal-Token")).isEqualTo(TOKEN);
            var body = JSON.readTree(request.body());
            UUID requestId = UUID.fromString(body.path("requestId").asString());
            assertThat(requestId.version()).isEqualTo(7);
            var expected = JSON.readTree("""
                    {"protocolVersion":1,"op":"submit","deadlineMs":10000,
                     "package":{"digest":"%s","apiVersion":{"major":1,"minor":5},
                       "outlineItemsDeclared":false,"answerStateDeclared":true,
                       "rules":{"sha256":"%s"},"questionSchema":{"sha256":"%s"},"answerSchema":{"sha256":"%s"}},
                     "input":{"data":{"text":"题目"},"answer":["A","B"]},"requestId":"%s"}
                    """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), requestId));
            assertThat(body).isEqualTo(expected);
            execute(client);
            assertThat(JSON.readTree(peer.requests().getLast().body()).path("requestId").asString())
                    .isNotEqualTo(requestId.toString());
            peer.assertHealthy();
        }
    }

    @Test
    void backgroundPriorityIsScopedAndRestoredEvenAfterFailure() throws Exception {
        try(var peer=new FakeRuleRunner()) {
            var client=client(peer.uri(),4);
            client.background(()->execute(client));
            assertThat(JSON.readTree(peer.requests().getFirst().body()).path("priority").asString()).isEqualTo("background");
            assertThatThrownBy(()->client.background(()->{throw new IllegalStateException("same");})).isInstanceOf(IllegalStateException.class);
            execute(client);
            assertThat(JSON.readTree(peer.requests().getLast().body()).has("priority")).isFalse();
        }
    }

    @Test
    void backgroundYieldsWhenOnlyForegroundReserveRemainsWithoutLeakingPermits() throws Exception {
        try(var peer=new FakeRuleRunner();var pool=Executors.newFixedThreadPool(3)) {
            var client=client(peer.uri(),2);var gate=peer.block(1);
            var running=pool.submit(()->client.background(()->execute(client)));gate.awaitEntered();
            try {
                for(int n=0;n<20;n++)assertThatThrownBy(()->client.background(()->execute(client))).isInstanceOf(RuleRunnerClient.BackgroundBusy.class);
                peer.respond(200,FakeRuleRunner.SUCCESS);
                assertThat(execute(client).ok()).isTrue();
                assertThat(peer.requests()).hasSize(2);
            } finally {gate.close();}
            assertThat(running.get(10,TimeUnit.SECONDS).ok()).isTrue();
            assertThat(client.background(()->execute(client)).ok()).isTrue();
            peer.assertHealthy();
        }
    }

    @Test
    void backgroundCanMakeProgressWithSinglePermitConfiguration() throws Exception {
        try(var peer=new FakeRuleRunner()) {
            var client=client(peer.uri(),1);assertThat(client.background(()->execute(client)).ok()).isTrue();
        }
    }

    @Test
    void explicitDeadlineIsSerializedInMilliseconds() throws Exception {
        try (var peer = new FakeRuleRunner()) {
            client(peer.uri(), 2).execute("submit", PACKAGE, INPUT, Duration.ofMillis(1234));
            assertThat(JSON.readTree(peer.requests().getFirst().body()).path("deadlineMs").asLong()).isEqualTo(1234);
        }
    }

    @Test
    void successfulResponsePreservesDataAndDoesNotRetry() throws Exception {
        try (var peer = new FakeRuleRunner()) {
            var result = execute(client(peer.uri(), 2));
            assertThat(result.ok()).isTrue();
            assertThat(result.data()).isEqualTo(JSON.readTree("""
                    {"projected":{"text":"中文答案","score":2}}
                    """));
            assertThat(result.code()).isNull();
            assertThat(peer.requests()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RULE_REJECTED", "RULE_TIMEOUT", "SCORE_UNAVAILABLE", "INVALID_OUTLINE_ITEMS",
            "INVALID_OUTLINE_STATES", "UNSUPPORTED_API_VERSION"})
    void ruleRejectionPreservesCodeAndDoesNotRetry(String code) throws Exception {
        try (var peer = new FakeRuleRunner()) {
            peer.respond(200, """
                    {"ok":false,"code":"%s","message":"diagnostic only"}
                    """.formatted(code));
            var result = execute(client(peer.uri(), 2));
            assertThat(result).isEqualTo(RuleResult.rejected(code));
            assertThat(result.data()).isNull();
            assertThat(peer.requests()).hasSize(1);
        }
    }

    @Test
    void rejectionWithoutCodeDefaultsToRuleRejected() throws Exception {
        try (var peer = new FakeRuleRunner()) {
            peer.respond(200, "{\"ok\":false,\"message\":\"rejected\"}");
            assertThat(execute(client(peer.uri(), 2))).isEqualTo(RuleResult.rejected("RULE_REJECTED"));
            assertThat(peer.requests()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 500, 401, 404})
    void non200ThrowsWithoutRetryAndReturnsAllPermits(int status) throws Exception {
        try (var peer = new FakeRuleRunner(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = client(peer.uri(), 2);
            peer.respond(status, "{\"ok\":false,\"code\":\"OVERLOADED\"}");
            assertThatThrownBy(() -> execute(client)).isInstanceOf(RuleRunnerException.class)
                    .hasMessageContaining(Integer.toString(status));
            assertThat(peer.requests()).hasSize(1);
            assertTwoConcurrentCallsSucceed(client, peer, callers);
        }
    }

    @Test
    void httpTimeoutIncludesOneSecondGraceDoesNotRetryAndReturnsAllPermits() throws Exception {
        try (var peer = new FakeRuleRunner(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = client(peer.uri(), 2);
            long start = System.nanoTime();
            try (var gate = peer.block(1)) {
                Future<Throwable> failure = callers.submit(() -> catchThrowable(() ->
                        client.execute("submit", PACKAGE, INPUT, Duration.ofMillis(100))));
                gate.awaitEntered();
                assertThat(failure.get(10, TimeUnit.SECONDS)).isInstanceOf(RuleRunnerException.class)
                        .hasCauseInstanceOf(HttpTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1000));
                assertThat(peer.requests()).hasSize(1);
                gate.close();
                gate.awaitFinished();
            }
            assertTwoConcurrentCallsSucceed(client, peer, callers);
        }
    }

    @Test
    void connectionFailureRetriesExactlyOnceAndReturnsAllPermits() throws Exception {
        int port = unusedPort();
        var client = client(uri(port), 2);
        AtomicInteger sends = observeSends(client, () -> {});
        assertThatThrownBy(() -> execute(client)).isInstanceOf(RuleRunnerException.class)
                .hasCauseInstanceOf(ConnectException.class);
        assertThat(sends).hasValue(2);
        try (var peer = new FakeRuleRunner(port); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            peer.start(port);
            assertTwoConcurrentCallsSucceed(client, peer, callers);
            assertThat(sends).hasValue(4);
        }
    }

    @Test
    void firstConnectionFailureThenServerStartsBeforeSingleRetry() throws Exception {
        int port = unusedPort();
        try (var peer = new FakeRuleRunner(port)) {
            var client = client(uri(port), 2);
            AtomicInteger sends = observeSends(client, () -> peer.start(port));
            assertThat(execute(client).ok()).isTrue();
            assertThat(sends).hasValue(2);
            assertThat(peer.requests()).hasSize(1);
            peer.assertHealthy();
        }
    }

    @Test
    void fullBulkheadFailsFastAndReturnsAllPermitsAfterRelease() throws Exception {
        try (var peer = new FakeRuleRunner(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = client(peer.uri(), 2);
            try (var gate = peer.block(2)) {
                Future<RuleResult> first = callers.submit(() -> execute(client));
                Future<RuleResult> second = callers.submit(() -> execute(client));
                gate.awaitEntered();
                long start = System.nanoTime();
                Future<Throwable> rejected = callers.submit(() -> catchThrowable(() -> execute(client)));
                assertThat(rejected.get(1, TimeUnit.SECONDS)).isInstanceOf(RuleRunnerException.class)
                        .hasMessageContaining("并发已满");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
                assertThat(peer.requests()).hasSize(2);
                gate.close();
                assertThat(first.get(10, TimeUnit.SECONDS).ok()).isTrue();
                assertThat(second.get(10, TimeUnit.SECONDS).ok()).isTrue();
                gate.awaitFinished();
            }
            assertTwoConcurrentCallsSucceed(client, peer, callers);
            assertThat(peer.peak()).isEqualTo(2);
        }
    }

    @Test
    void repeatedRejectedAcquisitionsCannotInflatePermitCount() throws Exception {
        try (var peer = new FakeRuleRunner(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = client(peer.uri(), 2);
            try (var gate = peer.block(2)) {
                Future<RuleResult> first = callers.submit(() -> execute(client));
                Future<RuleResult> second = callers.submit(() -> execute(client));
                gate.awaitEntered();
                for (int attempt = 0; attempt < 8; attempt++) {
                    Future<Throwable> rejected = callers.submit(() -> catchThrowable(() -> execute(client)));
                    assertThat(rejected.get(1, TimeUnit.SECONDS)).isInstanceOf(RuleRunnerException.class)
                            .hasMessageContaining("并发已满");
                }
                assertThat(peer.requests()).hasSize(2);
                gate.close();
                assertThat(first.get(10, TimeUnit.SECONDS).ok()).isTrue();
                assertThat(second.get(10, TimeUnit.SECONDS).ok()).isTrue();
                gate.awaitFinished();
            }
            assertTwoConcurrentCallsSucceed(client, peer, callers);
            assertThat(peer.peak()).isEqualTo(2);
        }
    }

    @Test
    void malformedJsonReturnsAllPermitsWithoutRetry() throws Exception {
        try (var peer = new FakeRuleRunner(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = client(peer.uri(), 2);
            peer.respond(200, "this is not JSON");
            assertThatThrownBy(() -> execute(client)).isInstanceOf(RuntimeException.class);
            assertThat(peer.requests()).hasSize(1);
            assertTwoConcurrentCallsSucceed(client, peer, callers);
        }
    }

    @Test
    void malformedJsonShouldBeReportedAsRuleRunnerException() throws Exception {
        try (var peer = new FakeRuleRunner()) {
            peer.respond(200, "this is not JSON");
            assertThatThrownBy(() -> execute(client(peer.uri(), 2))).isInstanceOf(RuleRunnerException.class);
        }
    }

    private static RuleRunnerClient client(URI uri, int concurrency) {
        return new RuleRunnerClient(new RuleRunnerProperties(uri, TOKEN, concurrency, DEADLINE), JSON);
    }

    private static RuleResult execute(RuleRunnerClient client) {
        return client.execute("submit", PACKAGE, INPUT);
    }

    private static void assertTwoConcurrentCallsSucceed(RuleRunnerClient client, FakeRuleRunner peer,
            ExecutorService callers) throws Exception {
        try (var gate = peer.block(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = callers.submit(() -> { start.await(); return execute(client); });
            var second = callers.submit(() -> { start.await(); return execute(client); });
            start.countDown();
            gate.awaitEntered();
            // Also prove recovery has not accidentally increased capacity above two.
            var rejected = callers.submit(() -> catchThrowable(() -> execute(client)));
            assertThat(rejected.get(1, TimeUnit.SECONDS)).isInstanceOf(RuleRunnerException.class)
                    .hasMessageContaining("并发已满");
            gate.close();
            assertThat(first.get(10, TimeUnit.SECONDS).ok()).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS).ok()).isTrue();
            gate.awaitFinished();
            peer.assertHealthy();
        }
    }

    private static URI uri(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static int unusedPort() throws Exception {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface AfterConnectFailure {
        void run() throws Exception;
    }

    /** Observe send calls, keeping the real JDK transport and real ConnectException. */
    private static AtomicInteger observeSends(RuleRunnerClient client, AfterConnectFailure afterFirstFailure)
            throws Exception {
        Field field = RuleRunnerClient.class.getDeclaredField("http");
        field.setAccessible(true);
        HttpClient transport = (HttpClient) field.get(client);
        HttpClient observed = mock(HttpClient.class);
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            int attempt = sends.incrementAndGet();
            HttpRequest request = invocation.getArgument(0);
            HttpResponse.BodyHandler<String> handler = invocation.getArgument(1);
            try {
                return transport.send(request, handler);
            } catch (ConnectException e) {
                if (attempt == 1) afterFirstFailure.run();
                throw e;
            }
        }).when(observed).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        field.set(client, observed);
        return sends;
    }
}
