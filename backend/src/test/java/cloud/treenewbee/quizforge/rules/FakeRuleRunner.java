package cloud.treenewbee.quizforge.rules;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A real loopback HTTP peer; gates hold responses until the test explicitly releases them. */
final class FakeRuleRunner implements AutoCloseable {
    static final String SUCCESS = """
            {"ok":true,"data":{"projected":{"text":"中文答案","score":2}},"elapsedMs":12}
            """;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private volatile Reply reply = new Reply(200, SUCCESS, null);
    private boolean started;

    FakeRuleRunner() throws IOException {
        this(0);
    }

    FakeRuleRunner(int port) throws IOException {
        server = HttpServer.create();
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        if (port == 0) start(0);
    }

    void start(int port) throws IOException {
        server.bind(new InetSocketAddress("127.0.0.1", port), 0);
        server.start();
        started = true;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respond(int status, String body) {
        reply = new Reply(status, body, null);
    }

    Gate block(int count) {
        Gate gate = new Gate(count);
        reply = new Reply(200, SUCCESS, gate);
        return gate;
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    int peak() {
        return peak.get();
    }

    void assertHealthy() {
        if (handlerFailure.get() != null) throw new AssertionError("Fake rule-runner handler failed", handlerFailure.get());
    }

    private void handle(HttpExchange exchange) {
        Reply current = reply;
        int running = active.incrementAndGet();
        peak.accumulateAndGet(running, Math::max);
        try (exchange) {
            requests.add(new Request(exchange.getRequestURI(), exchange.getRequestMethod(),
                    exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
            if (current.gate() != null) {
                current.gate().entered.countDown();
                if (!current.gate().release.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test did not release HTTP response");
                }
            }
            byte[] bytes = current.body().getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(current.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // A timed-out client has already closed its connection when its gate is released.
            if (current.gate() == null) handlerFailure.compareAndSet(null, e);
        } catch (Throwable e) {
            handlerFailure.compareAndSet(null, e);
        } finally {
            active.decrementAndGet();
            if (current.gate() != null) current.gate().finished.countDown();
        }
    }

    @Override
    public void close() {
        if (reply.gate() != null) reply.gate().release.countDown();
        if (started) server.stop(0);
        executor.shutdownNow();
    }

    record Request(URI uri, String method, Headers headers, String body) {}
    private record Reply(int status, String body, Gate gate) {}

    static final class Gate implements AutoCloseable {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished;

        private Gate(int count) {
            entered = new CountDownLatch(count);
            finished = new CountDownLatch(count);
        }

        void awaitEntered() throws InterruptedException {
            if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Requests did not reach HTTP server");
        }

        void awaitFinished() throws InterruptedException {
            if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("HTTP handlers did not finish");
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
