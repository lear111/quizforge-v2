package cloud.treenewbee.quizforge.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 测试内启动的假上游（兼容 OpenAI 的 /chat/completions）。行为由 API Key 中的模式决定，例如 {@code sk-fake-ok-…}：
 * ok、nousage、tool、stream、slowstream、hold（等测试放行）、401、403、429、500、timeout、arrears。
 * 不调用任何真实模型。
 */
final class FakeUpstream implements AutoCloseable {

    record Request(String key, String body) {
    }

    static final int PROMPT_TOKENS = 1000;
    static final int COMPLETION_TOKENS = 500;

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    /** hold 模式：到达的请求数（测试据此判断“已在进行中”），以及放行信号。 */
    final Semaphore holdArrived = new Semaphore(0);
    volatile CountDownLatch holdRelease = new CountDownLatch(1);
    /** slowstream 模式：每个片段之间等待测试放行（用于在流式中途断开）。 */
    final Semaphore slowChunks = new Semaphore(0);
    volatile String gradingText = "{\"score\":2,\"feedback\":\"本机假模型反馈\"}";
    volatile int malformedReplies;
    volatile boolean holdGrading;

    FakeUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    int calls(String key) {
        return calls.getOrDefault(key, new AtomicInteger()).get();
    }

    void reset() {
        requests.clear();
        calls.clear();
        holdArrived.drainPermits();
        holdRelease = new CountDownLatch(1);
        slowChunks.drainPermits();
        gradingText = "{\"score\":2,\"feedback\":\"本机假模型反馈\"}";
        malformedReplies = 0;
        holdGrading = false;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(key, body));
        calls.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        String mode = key.split("-")[2];
        try {
            switch (mode) {
                case "grading" -> {
                    if (holdGrading) {
                        holdArrived.release();
                        holdRelease.await(30, TimeUnit.SECONDS);
                    }
                    String text = malformedReplies-- > 0 ? "不是 JSON" : gradingText;
                    String escaped = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(text);
                    json(exchange, 200, completion("\"content\":" + escaped, "stop", true));
                }
                case "ok" -> json(exchange, 200, completion("\"content\":\"OK\"", "stop", true));
                case "nousage" -> json(exchange, 200, completion("\"content\":\"OK\"", "stop", false));
                case "tool" -> json(exchange, 200, body.matches("(?s).*\"role\"\\s*:\\s*\"tool\".*")
                        ? completion("\"content\":\"北京今天晴\"", "stop", true)
                        : completion("\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]",
                                "tool_calls", true));
                case "hold" -> {
                    holdArrived.release();
                    holdRelease.await(30, TimeUnit.SECONDS);
                    json(exchange, 200, completion("\"content\":\"OK\"", "stop", true));
                }
                case "stream", "slowstream" -> stream(exchange, mode.equals("slowstream"));
                case "401" -> json(exchange, 401, error("Incorrect API key provided", "invalid_api_key"));
                case "403" -> json(exchange, 403, error("Forbidden", "forbidden"));
                case "429" -> json(exchange, 429, error("Rate limit reached", "rate_limit_exceeded"));
                case "500" -> json(exchange, 500, error("Internal error", "internal_error"));
                case "arrears" -> json(exchange, 400, error("Access denied, account is in arrears", "Arrearage"));
                case "timeout" -> {
                    Thread.sleep(6_000);
                    json(exchange, 200, completion("\"content\":\"late\"", "stop", true));
                }
                default -> json(exchange, 500, error("unknown fake mode", "fake"));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端放弃了连接（例如超时）。
        } finally {
            exchange.close();
        }
    }

    private void stream(HttpExchange exchange, boolean slow) throws IOException, InterruptedException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        String[] pieces = {"一", "二", "三", "四", "五"};
        for (int i = 0; i < pieces.length; i++) {
            if (slow && i > 0 && !slowChunks.tryAcquire(20, TimeUnit.SECONDS)) return;
            event(out, chunk("{\"role\":\"assistant\",\"content\":\"" + pieces[i] + "\"}", null));
        }
        event(out, chunk("{}", "\"stop\""));
        event(out, "{\"id\":\"fake\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake\",\"choices\":[],"
                + usage());
        event(out, "[DONE]");
    }

    private static void event(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String chunk(String delta, String finish) {
        return "{\"id\":\"fake\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake\",\"choices\":[{\"index\":0,"
                + "\"delta\":" + delta + ",\"finish_reason\":" + finish + "}]}";
    }

    private static String completion(String message, String finish, boolean withUsage) {
        return "{\"id\":\"fake\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fake\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\"," + message + "},\"finish_reason\":\"" + finish + "\"}]"
                + (withUsage ? "," + usage() : "}");
    }

    private static String usage() {
        return "\"usage\":{\"prompt_tokens\":" + PROMPT_TOKENS + ",\"completion_tokens\":" + COMPLETION_TOKENS
                + ",\"total_tokens\":" + (PROMPT_TOKENS + COMPLETION_TOKENS) + "}}";
    }

    private static String error(String message, String code) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"error\",\"code\":\"" + code + "\"}}";
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
