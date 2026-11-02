package cloud.treenewbee.quizforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.ai.application.AiBalanceService;
import cloud.treenewbee.quizforge.ai.application.AiGateway;
import cloud.treenewbee.quizforge.ai.application.AiGateway.AiReply;
import cloud.treenewbee.quizforge.ai.application.UpstreamKeyService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L5.2 模型接入：用测试内启动的假上游覆盖余额检查、并发、Key 切换、计费、流式断开、图片上限与日志。
 * 不调用真实模型。
 */
@ExtendWith(OutputCaptureExtension.class)
class AiGatewayIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-gateway.example.test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final FakeUpstream UPSTREAM;
    /** 每百万 token：输入 3 元、输出 9 元（旧平台默认）。1000 输入 + 500 输出 = ceil(75,000,000 / 1,000,000) = 75。 */
    private static final long INPUT_PRICE = 30_000;
    private static final long OUTPUT_PRICE = 90_000;
    private static final long EXPECTED_COST = 75;

    static {
        try {
            UPSTREAM = new FakeUpstream();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("qf.security.allowed-origin", () -> ORIGIN);
        registry.add("qf.ai.base-url", UPSTREAM::baseUrl);
        registry.add("qf.ai.model", () -> "qwen-fake-model");
        registry.add("qf.ai.call-timeout", () -> "2s");
        registry.add("qf.ai.stream-timeout", () -> "20s");
        registry.add("qf.ai.max-output-tokens", () -> "1024");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiGateway gateway;
    @Autowired AiBalanceService balances;
    @Autowired UpstreamKeyService keys;

    private final HttpClient http = HttpClient.newHttpClient();
    private Session admin;

    @BeforeEach
    void cleanPool() throws Exception {
        jdbc.update("DELETE FROM ai_upstream_key");
        UPSTREAM.reset();
        admin = admin();
    }

    @AfterEach
    void close() {
        UPSTREAM.holdRelease.countDown();
        http.close();
    }

    // ------------------------------------------------------------------ 正常调用与计费

    @Test
    void chatChargesByUsageAndTheKeysPricesOncePerRef() throws Exception {
        String key = addKey("ok");
        Session user = register();
        String ref = "task-" + UUID.randomUUID();
        AiReply reply = gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好"))
                .modelName("caller-cannot-pick").build(), ref);
        assertThat(reply.text()).isEqualTo("OK");
        assertThat(reply.inputTokens()).isEqualTo(FakeUpstream.PROMPT_TOKENS);
        assertThat(reply.outputTokens()).isEqualTo(FakeUpstream.COMPLETION_TOKENS);
        assertThat(reply.cost()).isEqualTo(EXPECTED_COST);
        assertThat(reply.alreadyCharged()).isFalse();
        assertThat(reply.balanceAfter()).isEqualTo(10_000 - EXPECTED_COST);
        assertThat(balance(user)).isEqualTo(10_000 - EXPECTED_COST);
        assertThat(jdbc.queryForObject("SELECT CONCAT(kind, '/', ref, '/', delta) FROM ai_ledger WHERE user_id = ? AND seq = 2",
                String.class, user.id())).isEqualTo("ai_call/" + ref + "/-" + EXPECTED_COST);

        // 模型 ID 用配置值，max_tokens 补上上限。
        JsonNode sent = JSON.readTree(UPSTREAM.requests.getLast().body());
        assertThat(sent.path("model").asString()).isEqualTo("qwen-fake-model");
        assertThat(sent.path("max_tokens").asInt(0) + sent.path("max_completion_tokens").asInt(0)).isEqualTo(1024);

        // 同一 ref 重试：照常返回回复，但不再扣费。
        AiReply retry = gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(), ref);
        assertThat(retry.alreadyCharged()).isTrue();
        assertThat(balance(user)).isEqualTo(10_000 - EXPECTED_COST);
        assertThat(jdbc.queryForObject("SELECT CONCAT(calls, '/', input_tokens, '/', output_tokens, '/', spent) "
                + "FROM ai_upstream_key WHERE id = ?", String.class, key)).isEqualTo("1/1000/500/" + EXPECTED_COST);
    }

    @Test
    void responsesWithoutUsageAreChargedTheMinimumUnit() throws Exception {
        addKey("nousage");
        Session user = register();
        AiReply reply = gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                "task-" + UUID.randomUUID());
        assertThat(reply.usageReported()).isFalse();
        assertThat(reply.cost()).isEqualTo(1);
        assertThat(balance(user)).isEqualTo(9_999);
    }

    @Test
    void toolCallsAreReturnedAndTheCallerRunsTheLoopWithOneChargePerCall() throws Exception {
        addKey("tool");
        Session user = register();
        ToolSpecification weather = ToolSpecification.builder().name("weather").description("查询天气")
                .parameters(JsonObjectSchema.builder().addStringProperty("city").required("city").build()).build();
        List<ChatMessage> messages = new ArrayList<>(List.of(UserMessage.from("北京天气怎样？")));
        AiReply first = gateway.chat(user.id(), ChatRequest.builder().messages(messages).toolSpecifications(weather).build(),
                "tutor-1:step-1");
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.arguments()).contains("北京");
        });
        assertThat(JSON.readTree(UPSTREAM.requests.getLast().body()).path("tools").get(0).path("function").path("name")
                .asString()).isEqualTo("weather");

        messages.add(first.message());
        ToolExecutionRequest call = first.toolCalls().getFirst();
        messages.add(ToolExecutionResultMessage.from(call, "晴，20℃"));
        AiReply second = gateway.chat(user.id(), ChatRequest.builder().messages(messages).toolSpecifications(weather).build(),
                "tutor-1:step-2");
        assertThat(second.text()).isEqualTo("北京今天晴");
        assertThat(second.toolCalls()).isEmpty();
        assertThat(balance(user)).isEqualTo(10_000 - 2 * EXPECTED_COST);
    }

    // ------------------------------------------------------------------ 余额与并发

    @Test
    void usersWithoutPositiveBalanceAreRejectedBeforeCallingTheModel() throws Exception {
        String key = addKey("ok");
        Session user = register();
        balances.charge(user.id(), 10_000, "drain-" + UUID.randomUUID());
        assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                "task-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(error.getMessage()).isEqualTo("余额不足，请充值");
                });
        assertThat(UPSTREAM.calls(apiKey(key))).isZero();
    }

    @Test
    void eachUserHasAtMostTwoCallsInFlightAndTheThirdIsRejectedImmediately() throws Exception {
        addKey("hold");
        Session user = register();
        Session other = register();
        List<CompletableFuture<AiReply>> running = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String ref = "hold-" + i + "-" + UUID.randomUUID();
            running.add(CompletableFuture.supplyAsync(() -> gateway.chat(user.id(),
                    ChatRequest.builder().messages(UserMessage.from("等一下")).build(), ref)));
        }
        assertThat(UPSTREAM.holdArrived.tryAcquire(2, 20, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("第三个")).build(),
                "hold-3-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_BUSY"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThatThrownBy(() -> gateway.stream(user.id(), ChatRequest.builder().messages(UserMessage.from("流式")).build(),
                "hold-4-" + UUID.randomUUID(), new Collector()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_BUSY"));
        // 别的用户不受影响。
        CompletableFuture<AiReply> others = CompletableFuture.supplyAsync(() -> gateway.chat(other.id(),
                ChatRequest.builder().messages(UserMessage.from("我也要")).build(), "hold-o-" + UUID.randomUUID()));
        assertThat(UPSTREAM.holdArrived.tryAcquire(1, 20, TimeUnit.SECONDS)).isTrue();
        UPSTREAM.holdRelease.countDown();
        for (CompletableFuture<AiReply> call : running) assertThat(call.get(20, TimeUnit.SECONDS).cost()).isEqualTo(EXPECTED_COST);
        others.get(20, TimeUnit.SECONDS);
        // 名额已归还。
        UPSTREAM.holdRelease = new java.util.concurrent.CountDownLatch(0);
        gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("再来")).build(), "hold-5-" + UUID.randomUUID());
        assertThat(balance(user)).isEqualTo(10_000 - 3 * EXPECTED_COST);
    }

    // ------------------------------------------------------------------ Key 切换与失败不扣费

    @Test
    void keysAreTriedInOrderAndRejectedKeysAreMarkedUnavailable(CapturedOutput output) throws Exception {
        String rejected = addKey("401");
        String throttled = addKey("429");
        String good = addKey("ok");
        Session user = register();
        AiReply reply = gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                "task-" + UUID.randomUUID());
        assertThat(reply.keyId()).isEqualTo(good);
        assertThat(UPSTREAM.requests).extracting(FakeUpstream.Request::key)
                .containsExactly(apiKey(rejected), apiKey(throttled), apiKey(good));
        assertThat(state(rejected)).isEqualTo("unavailable");
        assertThat(state(throttled)).isEqualTo("ok");
        assertThat(jdbc.queryForObject("SELECT last_error FROM ai_upstream_key WHERE id = ?", String.class, throttled))
                .contains("429");
        assertThat(output.getAll()).contains("被上游拒绝");

        // 不可用的 Key 不再参与调用。
        UPSTREAM.reset();
        gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(), "task-" + UUID.randomUUID());
        assertThat(UPSTREAM.requests).extracting(FakeUpstream.Request::key).containsExactly(apiKey(throttled), apiKey(good));
        assertThat(balance(user)).isEqualTo(10_000 - 2 * EXPECTED_COST);
    }

    @Test
    void atMostThreeKeysAreTriedAndFailuresAreNotCharged() throws Exception {
        String a = addKey("500");
        String b = addKey("timeout");
        String c = addKey("403");
        String d = addKey("ok");
        Session user = register();
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                "task-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("AI_UPSTREAM_FAILED");
                    assertThat(error.getMessage()).contains("不扣费");
                });
        // 超时 2 秒：三个 Key 的总耗时有上限（单次超时 × 3）。
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        assertThat(UPSTREAM.calls(apiKey(a))).isEqualTo(1);
        assertThat(UPSTREAM.calls(apiKey(b))).isEqualTo(1);
        assertThat(UPSTREAM.calls(apiKey(c))).isEqualTo(1);
        assertThat(UPSTREAM.calls(apiKey(d))).isZero();
        assertThat(state(c)).isEqualTo("unavailable");
        assertThat(balance(user)).isEqualTo(10_000);
        assertThat(ledgerCount(user)).isEqualTo(1);
    }

    @Test
    void eachFailureKindIsFreeAndAccountProblemsDisableTheKey() throws Exception {
        Session user = register();
        for (String mode : List.of("401", "403", "429", "500", "timeout", "arrears")) {
            jdbc.update("DELETE FROM ai_upstream_key");
            String key = addKey(mode);
            assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                    "task-" + UUID.randomUUID())).as(mode).isInstanceOf(ApiException.class);
            assertThat(state(key)).as(mode).isEqualTo(List.of("401", "403", "arrears").contains(mode) ? "unavailable" : "ok");
        }
        assertThat(balance(user)).isEqualTo(10_000);
        assertThat(ledgerCount(user)).isEqualTo(1);
        // 没有可用的 Key：直接提示暂不可用。
        jdbc.update("DELETE FROM ai_upstream_key");
        assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                "task-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ 流式

    @Test
    void streamingDeliversPiecesAndChargesFromTheFinalUsage() throws Exception {
        addKey("500");
        addKey("stream");
        Session user = register();
        Collector collector = new Collector();
        AiReply reply = gateway.stream(user.id(), ChatRequest.builder().messages(UserMessage.from("数到五")).build(),
                "stream-" + UUID.randomUUID(), collector).get(20, TimeUnit.SECONDS);
        assertThat(String.join("", collector.pieces)).isEqualTo("一二三四五");
        assertThat(collector.completed).containsExactly(reply);
        assertThat(reply.text()).isEqualTo("一二三四五");
        assertThat(reply.cost()).isEqualTo(EXPECTED_COST);
        assertThat(balance(user)).isEqualTo(10_000 - EXPECTED_COST);
        JsonNode sent = JSON.readTree(UPSTREAM.requests.getLast().body());
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void streamingKeepsReadingAfterTheClientDisconnectsAndStillCharges() throws Exception {
        addKey("slowstream");
        Session user = register();
        Collector collector = new Collector();
        collector.failAfter = 1;
        String ref = "stream-" + UUID.randomUUID();
        CompletableFuture<AiReply> future = gateway.stream(user.id(),
                ChatRequest.builder().messages(UserMessage.from("数到五")).build(), ref, collector);
        // 第一个片段之后接收方“断开”；随后放行上游剩余的片段。
        UPSTREAM.slowChunks.release(4);
        AiReply reply = future.get(20, TimeUnit.SECONDS);
        assertThat(collector.pieces).containsExactly("一");
        assertThat(reply.text()).isEqualTo("一二三四五");
        assertThat(reply.cost()).isEqualTo(EXPECTED_COST);
        assertThat(balance(user)).isEqualTo(10_000 - EXPECTED_COST);
        assertThat(jdbc.queryForObject("SELECT ref FROM ai_ledger WHERE user_id = ? AND kind = 'ai_call'", String.class,
                user.id())).isEqualTo(ref);
    }

    @Test
    void streamingFailuresBeforeAnyOutputAreNotCharged() throws Exception {
        addKey("429");
        addKey("500");
        Session user = register();
        Collector collector = new Collector();
        CompletableFuture<AiReply> future = gateway.stream(user.id(),
                ChatRequest.builder().messages(UserMessage.from("你好")).build(), "stream-" + UUID.randomUUID(), collector);
        assertThatThrownBy(() -> future.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(ApiException.class);
        assertThat(collector.errors).hasSize(1);
        assertThat(collector.completed).isEmpty();
        assertThat(balance(user)).isEqualTo(10_000);
        // 名额已归还：同一用户还能再发起两个调用。
        jdbc.update("DELETE FROM ai_upstream_key");
        addKey("ok");
        gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(), "a-" + UUID.randomUUID());
        gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(), "b-" + UUID.randomUUID());
    }

    // ------------------------------------------------------------------ 请求检查

    @Test
    void imageSizeAndMaxTokensAreBounded() throws Exception {
        String key = addKey("ok");
        Session user = register();
        String small = base64(1024);
        AiReply reply = gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from(TextContent.from("看图"),
                ImageContent.from(small, "image/png"))).build(), "img-" + UUID.randomUUID());
        assertThat(reply.cost()).isEqualTo(EXPECTED_COST);
        assertThat(UPSTREAM.requests.getLast().body()).contains("data:image/png;base64," + small.substring(0, 16));

        assertRejected(UserMessage.from(ImageContent.from(base64(4 * 1024 * 1024 + 3), "image/png")), HttpStatus.CONTENT_TOO_LARGE);
        String almostFour = base64(4 * 1024 * 1024 - 3);
        assertRejected(UserMessage.from(ImageContent.from(almostFour, "image/png"), ImageContent.from(almostFour, "image/jpeg"),
                ImageContent.from(almostFour, "image/webp"), ImageContent.from(base64(12), "image/png")), HttpStatus.CONTENT_TOO_LARGE);
        assertRejected(UserMessage.from(ImageContent.from(small, "image/gif")), HttpStatus.BAD_REQUEST);
        assertRejected(UserMessage.from(ImageContent.from("https://example.test/a.png")), HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好"))
                .maxOutputTokens(1025).build(), "t-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(UPSTREAM.calls(apiKey(key))).isEqualTo(1);
        assertThat(balance(user)).isEqualTo(10_000 - EXPECTED_COST);
    }

    // ------------------------------------------------------------------ 日志

    @Test
    void logsNeverContainKeysOrRequestContent(CapturedOutput output) throws Exception {
        String rejected = addKey("401");
        String good = addKey("ok");
        addKey("stream");
        Session user = register();
        String secretText = "题目正文-" + UUID.randomUUID();
        String ref = "log-" + UUID.randomUUID();
        gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from(secretText)).build(), ref);
        gateway.stream(user.id(), ChatRequest.builder().messages(UserMessage.from(secretText)).build(),
                "log-s-" + UUID.randomUUID(), new Collector()).get(20, TimeUnit.SECONDS);
        String logs = output.getAll();
        for (String id : List.of(rejected, good)) assertThat(logs).doesNotContain(apiKey(id));
        assertThat(logs).doesNotContain(secretText).doesNotContain("sk-fake-");
        assertThat(logs).contains("ref=" + ref).contains("user=" + user.id()).contains("key=" + good)
                .contains("in=1000").contains("out=500").contains("cost=" + EXPECTED_COST).contains("result=ok");
    }

    // ------------------------------------------------------------------ 管理后台

    @Test
    void adminManagesTheKeyPoolWithoutEverReadingKeysBack() throws Exception {
        String secret = "sk-fake-ok-" + UUID.randomUUID();
        HttpResponse<String> created = send("POST", "/api/admin/ai-keys", JSON.writeValueAsString(Map.of("apiKey", secret,
                "note", "主力", "inputPrice", INPUT_PRICE, "outputPrice", OUTPUT_PRICE)), admin.cookie());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).doesNotContain(secret).contains(secret.substring(secret.length() - 4));
        String first = JSON.readTree(created.body()).path("id").asString();
        String second = addKey("ok");

        JsonNode status = json(send("GET", "/api/admin/ai-service", null, admin.cookie()));
        assertThat(status.toString()).doesNotContain(secret).doesNotContain("sk-fake-");
        assertThat(status.path("config").path("model").asString()).isEqualTo("qwen-fake-model");
        assertThat(status.path("keys")).extracting(node -> node.path("id").asString()).containsExactly(first, second);
        assertThat(jdbc.queryForObject("SELECT key_cipher FROM ai_upstream_key WHERE id = ?", String.class, first))
                .doesNotContain(secret).doesNotContain(secret.substring(12));

        assertThat(send("PUT", "/api/admin/ai-keys/order", JSON.writeValueAsString(Map.of("ids", List.of(second, first))),
                admin.cookie()).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/admin/ai-keys/order", JSON.writeValueAsString(Map.of("ids", List.of(second))),
                admin.cookie()).statusCode()).isEqualTo(409);
        assertThat(send("PUT", "/api/admin/ai-keys/" + first + "/prices", "{\"inputPrice\":1,\"outputPrice\":2}",
                admin.cookie()).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/admin/ai-keys/" + first + "/prices", "{\"inputPrice\":-1,\"outputPrice\":2}",
                admin.cookie()).statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/api/admin/ai-keys/" + second + "/enabled", "{\"enabled\":false}", admin.cookie()).statusCode())
                .isEqualTo(200);
        // 停用的 Key 不参与调用：只用第一个（单价已改为 1 / 2），费用 ceil((1000 + 1000) / 1e6) = 1。
        Session user = register();
        AiReply reply = gateway.chat(user.id(), ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                "task-" + UUID.randomUUID());
        assertThat(reply.keyId()).isEqualTo(first);
        assertThat(reply.cost()).isEqualTo(1);

        // 测试连接：费用计入管理员自己。
        JsonNode test = json(send("POST", "/api/admin/ai-keys/" + second + "/test", null, admin.cookie()));
        assertThat(test.path("ok").asBoolean()).isTrue();
        assertThat(balance(admin)).isEqualTo(10_000 - EXPECTED_COST);
        assertThat(send("DELETE", "/api/admin/ai-keys/" + second, null, admin.cookie()).statusCode()).isEqualTo(204);

        for (String body : List.of("{\"apiKey\":\"short\",\"inputPrice\":1,\"outputPrice\":1}",
                "{\"apiKey\":\"sk-fake-ok-123456\",\"inputPrice\":10000001,\"outputPrice\":1}",
                "{\"apiKey\":\"sk-fake-ok-123456\",\"inputPrice\":1,\"outputPrice\":1,\"expiresAt\":\"2000-01-01T00:00:00Z\"}")) {
            assertThat(send("POST", "/api/admin/ai-keys", body, admin.cookie()).statusCode()).as(body).isEqualTo(400);
        }
        List<String> actions = jdbc.queryForList("SELECT action FROM admin_audit WHERE module = 'ai' AND actor_id = ? "
                + "ORDER BY created_at, id", String.class, admin.id());
        assertThat(actions).containsExactly("ai-key-added", "ai-key-added", "ai-keys-reordered", "ai-key-prices",
                "ai-key-disabled", "ai-key-tested", "ai-key-deleted");
        assertThat(jdbc.queryForList("SELECT detail FROM admin_audit WHERE module = 'ai' AND actor_id = ?", String.class,
                admin.id()).toString()).doesNotContain(secret);
    }

    @Test
    void thePoolHoldsAtMostTwentyKeysAndOnlyAdminsCanManageIt() throws Exception {
        for (int i = 0; i < 20; i++) addKey("ok");
        assertThat(send("POST", "/api/admin/ai-keys", "{\"apiKey\":\"sk-fake-ok-1234567890\",\"inputPrice\":1,\"outputPrice\":1}",
                admin.cookie()).statusCode()).isEqualTo(409);
        Session member = register();
        String id = jdbc.queryForObject("SELECT id FROM ai_upstream_key LIMIT 1", String.class);
        for (String[] endpoint : List.of(
                new String[] {"GET", "/api/admin/ai-service", null},
                new String[] {"POST", "/api/admin/ai-keys", "{\"apiKey\":\"sk-fake-ok-1234567890\",\"inputPrice\":1,\"outputPrice\":1}"},
                new String[] {"PUT", "/api/admin/ai-keys/" + id + "/enabled", "{\"enabled\":false}"},
                new String[] {"PUT", "/api/admin/ai-keys/" + id + "/prices", "{\"inputPrice\":1,\"outputPrice\":1}"},
                new String[] {"PUT", "/api/admin/ai-keys/order", "{\"ids\":[]}"},
                new String[] {"POST", "/api/admin/ai-keys/" + id + "/test", null},
                new String[] {"DELETE", "/api/admin/ai-keys/" + id, null})) {
            assertThat(send(endpoint[0], endpoint[1], endpoint[2], member.cookie()).statusCode()).as(endpoint[1]).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_upstream_key", Integer.class)).isEqualTo(20);
        assertThat(UPSTREAM.requests).isEmpty();
    }

    // ------------------------------------------------------------------ 辅助

    private record Session(String id, String cookie) {
    }

    private static final class Collector implements AiGateway.AiStreamHandler {
        final List<String> pieces = new CopyOnWriteArrayList<>();
        final List<AiReply> completed = new CopyOnWriteArrayList<>();
        final List<ApiException> errors = new CopyOnWriteArrayList<>();
        int failAfter = Integer.MAX_VALUE;

        @Override
        public void onPartial(String text) {
            if (pieces.size() >= failAfter) throw new IllegalStateException("client disconnected");
            pieces.add(text);
        }

        @Override
        public void onComplete(AiReply reply) {
            completed.add(reply);
        }

        @Override
        public void onError(ApiException error) {
            errors.add(error);
        }
    }

    private void assertRejected(UserMessage message, HttpStatus status) {
        assertThatThrownBy(() -> gateway.chat(admin.id(), ChatRequest.builder().messages(message).build(),
                "img-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    private static String base64(int bytes) {
        return java.util.Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    private String addKey(String mode) {
        return keys.add(admin.id(), "sk-fake-" + mode + "-" + UUID.randomUUID(), mode, INPUT_PRICE, OUTPUT_PRICE, null).id();
    }

    /** 测试中的 Key 是 sk-fake-<模式>-<UUID>，从审计以外的地方取回：直接用解密后的候选 Key。 */
    private String apiKey(String id) {
        return keys.key(id).apiKey();
    }

    private String state(String id) {
        return jdbc.queryForObject("SELECT state FROM ai_upstream_key WHERE id = ?", String.class, id);
    }

    private long balance(Session user) {
        return jdbc.queryForObject("SELECT balance FROM ai_account WHERE user_id = ?", Long.class, user.id());
    }

    private long ledgerCount(Session user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_ledger WHERE user_id = ?", Long.class, user.id());
    }

    private Session admin() throws Exception {
        Session session = register();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", session.id());
        return session;
    }

    private Session register() throws Exception {
        String email = "gw-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime() + "@example.test";
        HttpResponse<String> response = send("POST", "/api/auth/register", JSON.writeValueAsString(
                Map.of("identifier", email, "name", "U", "password", "pass12345")), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Session(JSON.readTree(response.body()).path("user").path("id").asString(),
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]);
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Origin", ORIGIN);
        if (body != null) request.header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
