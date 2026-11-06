package cloud.treenewbee.quizforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.ai.application.AiBalanceService;
import cloud.treenewbee.quizforge.ai.application.RedeemCodeService;
import cloud.treenewbee.quizforge.ai.application.UpstreamKeyService;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.client.application.ClientAiService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 客户端兼容接口的契约测试（遗留契约 C-05）：请求 → 响应结构与错误码，以 docs/legacy/01-contracts.md 为准。
 * 不调用真实模型，也不连接任何服务器。逐字节下载经过 Nginx，见 frontend/test/browser/client-compat.spec.mjs。
 */
@Timeout(120)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ClientContractIntegrationTest.TimeConfig.class)
class ClientContractIntegrationTest extends MySqlTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN = "http://client.example.test";
    static final Pattern TOKEN = Pattern.compile("qfd_[A-Za-z0-9_-]{43}");
    static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    static final FakeUpstream UPSTREAM = upstream();

    static FakeUpstream upstream() {
        try {
            return new FakeUpstream();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-05T04:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock clientClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.ai.base-url", UPSTREAM::baseUrl);
        registry.add("qf.ai.model", () -> "fake-model");
        registry.add("qf.ai.call-timeout", () -> "10s");
        registry.add("qf.security.allowed-origin", () -> ORIGIN);
        registry.add("qf.client.monthly-budget-bytes", () -> "1000000");
        registry.add("qf.client.ip-daily-budget-bytes", () -> "1000");
    }

    @Autowired AuthService auth;
    @Autowired AccountAdminService admins;
    @Autowired AiBalanceService balances;
    @Autowired UpstreamKeyService keys;
    @Autowired RedeemCodeService codes;
    @Autowired CatalogMapper catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    record User(String id, String cookie) {
    }

    record Grant(ObjectNode exchange, String redirect) {
    }

    User user;
    User admin;

    @BeforeEach
    void fresh() {
        // 每个用例进入新的限频窗口；用例之间不共享 Key 与流量。
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        UPSTREAM.reset();
        jdbc.update("DELETE FROM ai_upstream_key");
        jdbc.update("DELETE FROM client_download_traffic");
        user = user();
        admin = user();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", admin.id());
    }

    @AfterAll
    void close() {
        UPSTREAM.close();
        http.close();
        jdbc.update("DELETE FROM ai_upstream_key");
    }

    // ------------------------------------------------------------------ 第 15 节

    @Test
    void configAndCapabilitiesReportWhatTheServerSupports() throws Exception {
        JsonNode config = json(get("/api/config", null, null), 200);
        assertThat(config.path("localImport").path("enabled").asBoolean()).isTrue();
        assertThat(config.path("localImport").path("protocolVersion").asInt()).isEqualTo(1);
        assertThat(config.path("localImport").path("scheme").asString()).isEqualTo("quizforge");
        assertThat(config.path("officialAi").path("enabled").asBoolean()).isTrue();
        assertThat(config.path("officialAi").path("protocolVersion").asInt()).isEqualTo(1);
        for (String field : List.of("maxArchiveMB", "registrationReady", "registrationMethods", "verificationMethods", "captchaReady",
                "authPolicy", "invitationRequired", "cloudEnabled", "practiceOrigin", "adminEnabled")) {
            assertThat(config.has(field)).as(field).isTrue();
        }
        JsonNode capabilities = json(get("/api/java/capabilities", null, null), 200);
        assertThat(capabilities.path("localImportEnabled").asBoolean()).isTrue();
        assertThat(capabilities.path("native").toString()).contains("devices", "ai-gateway", "local-import");
        assertThat(capabilities.has("phase") && capabilities.has("practiceOrigin") && capabilities.has("legacyOrigin")
                && capabilities.has("pending")).isTrue();
    }

    // ------------------------------------------------------------------ 第 1 节：授权页

    @Test
    void authorizeRejectsAnythingButTheExactParametersWithoutRedirecting() throws Exception {
        String verifier = verifier();
        Map<String, String> good = authorizeParams("web", webRedirect(), verifier, "我的电脑");
        assertThat(get("/connect/authorize?" + query(good), user.cookie(), null).statusCode()).isEqualTo(200);
        List<Map<String, String>> bad = new ArrayList<>();
        for (var change : List.of(Map.of("v", "2"), Map.of("client", "attacker"), Map.of("code_challenge_method", "plain"),
                Map.of("state", "short"), Map.of("code_challenge", "x"), Map.of("device_name", " "), Map.of("device_name", "长".repeat(41)),
                Map.of("redirect_uri", "https://evil.example/hub/connect/callback"),
                Map.of("redirect_uri", "http://127.0.0.1:80/hub/connect/callback"),
                Map.of("redirect_uri", "http://127.0.0.1:8787/other"),
                Map.of("redirect_uri", "quizforge://hub/connect"))) {
            Map<String, String> copy = new LinkedHashMap<>(good);
            copy.putAll(change);
            bad.add(copy);
        }
        Map<String, String> extra = new LinkedHashMap<>(good);
        extra.put("extra", "1");
        bad.add(extra);
        Map<String, String> missing = new LinkedHashMap<>(good);
        missing.remove("state");
        bad.add(missing);
        for (Map<String, String> params : bad) {
            var response = get("/connect/authorize?" + query(params), user.cookie(), null);
            assertThat(response.statusCode()).as(params.toString()).isEqualTo(400);
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        }
        // 重复参数；android 只能回到固定地址。
        assertThat(get("/connect/authorize?" + query(good) + "&state=duplicate-state-duplicate-state", user.cookie(), null).statusCode()).isEqualTo(400);
        assertThat(get("/connect/authorize?" + query(authorizeParams("android", webRedirect(), verifier, "手机")), user.cookie(), null).statusCode()).isEqualTo(400);
    }

    @Test
    void anonymousBrowsersLogInFirstAndReturnToTheSameRequest() throws Exception {
        String verifier = verifier();
        var anonymous = get("/connect/authorize?" + query(authorizeParams("web", webRedirect(), verifier, "<b>电脑</b>")), null, null);
        assertThat(anonymous.statusCode()).isEqualTo(303);
        String location = anonymous.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("/?returnTo=%2Fconnect%2Fauthorize%3Frequest%3D");
        String key = location.substring(location.lastIndexOf("%3D") + 3);
        assertThat(key).matches("[a-f0-9]{64}");
        var page = get("/connect/authorize?request=" + key, user.cookie(), null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("data-request=\"" + key + "\"").contains("&lt;b&gt;电脑&lt;/b&gt;（电脑）").doesNotContain("<b>电脑");
        assertThat(page.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'");
        // 授权请求 10 分钟有效。
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(get("/connect/authorize?request=" + key, user.cookie(), null).statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ 第 2、3 节：授权码 + PKCE

    @Test
    void authorizationCodeWithPkceIssuesAHashedDeviceTokenForEachClient() throws Exception {
        for (String client : List.of("web", "android")) {
            String redirect = client.equals("web") ? webRedirect() : "quizforge://hub/connect";
            Grant grant = grant(user, client, redirect, verifier(), "设备 " + client);
            assertThat(grant.redirect()).startsWith(redirect + "?code=").contains("&state=");
            var response = exchange(grant.exchange());
            JsonNode body = json(response, 200);
            assertThat(fields(body)).containsExactly("token", "device", "user");
            assertThat(body.path("token").asString()).matches(TOKEN);
            assertThat(fields(body.path("device"))).containsExactly("id", "name", "client", "created");
            assertThat(body.path("device").path("client").asString()).isEqualTo(client);
            assertThat(body.path("device").path("name").asString()).isEqualTo("设备 " + client);
            assertThat(body.path("device").path("created").asString()).matches(ISO);
            assertThat(fields(body.path("user"))).containsExactly("name");
            // 库中只有令牌的 SHA-256。
            byte[] stored = jdbc.queryForObject("SELECT token_hash FROM auth_device WHERE id = ?", byte[].class, body.path("device").path("id").asString());
            assertThat(stored).isEqualTo(sha256(body.path("token").asString()));
        }
    }

    @Test
    void denyingReturnsAccessDeniedAndTheRequestCanOnlyBeDecidedOnce() throws Exception {
        String key = consentKey(user, authorizeParams("web", webRedirect(), verifier(), "电脑"));
        var denied = decide(user, key, "deny");
        assertThat(json(denied, 200).path("redirect").asString()).startsWith(webRedirect() + "?error=access_denied&state=");
        assertError(decide(user, key, "allow"), 410, "CONNECT_REQUEST_REJECTED");
        String other = consentKey(user, authorizeParams("web", webRedirect(), verifier(), "电脑"));
        assertError(decide(user, other, "maybe"), 400, "CONNECT_REQUEST_REJECTED");
        assertError(decide(null, other, "allow"), 401, "CONNECT_REQUEST_REJECTED");
        // 确认授权是浏览器写请求：没有本站 Origin 时拒绝。
        var cross = http.send(HttpRequest.newBuilder(uri("/api/connect/authorize")).header("Cookie", user.cookie())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"request\":\"" + other + "\",\"decision\":\"allow\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(cross.statusCode()).isEqualTo(403);
    }

    @Test
    void everyFailedExchangeIsInvalidGrantAndBurnsTheCode() throws Exception {
        // 错误的 verifier：失败，并且授权码作废，正确的 verifier 也不能再用。
        Grant wrong = grant(user, "web", webRedirect(), verifier(), "电脑");
        ObjectNode tampered = wrong.exchange().deepCopy();
        tampered.put("code_verifier", "z".repeat(43));
        assertError(exchange(tampered), 400, "INVALID_GRANT");
        assertError(exchange(wrong.exchange()), 400, "INVALID_GRANT");
        // 授权码只能用一次。
        Grant once = grant(user, "web", webRedirect(), verifier(), "电脑");
        json(exchange(once.exchange()), 200);
        assertError(exchange(once.exchange()), 400, "INVALID_GRANT");
        // client 或回调地址与签发时不一致。
        Grant client = grant(user, "web", webRedirect(), verifier(), "电脑");
        assertError(exchange(client.exchange().deepCopy().put("client", "android")), 400, "INVALID_GRANT");
        Grant redirect = grant(user, "web", webRedirect(), verifier(), "电脑");
        assertError(exchange(redirect.exchange().deepCopy().put("redirect_uri", "http://localhost:9999/hub/connect/callback")), 400, "INVALID_GRANT");
        // 字段必须恰好这些；格式不对。
        Grant extra = grant(user, "web", webRedirect(), verifier(), "电脑");
        assertError(exchange(extra.exchange().deepCopy().put("extra", 1)), 400, "INVALID_GRANT");
        assertError(exchange(extra.exchange().deepCopy().put("v", 2)), 400, "INVALID_GRANT");
        assertError(exchange(extra.exchange().deepCopy().put("code", "bad")), 400, "INVALID_GRANT");
        assertError(exchange(extra.exchange().deepCopy().put("code_verifier", "短")), 400, "INVALID_GRANT");
        // 120 秒后过期。
        Grant expired = grant(user, "web", webRedirect(), verifier(), "电脑");
        clock.now = clock.now.plusSeconds(121);
        assertError(exchange(expired.exchange()), 400, "INVALID_GRANT");
    }

    @Test
    void concurrentExchangesOfOneCodeIssueExactlyOneToken() throws Exception {
        Grant grant = grant(user, "android", "quizforge://hub/connect", verifier(), "手机");
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> exchange(grant.exchange()));
            var b = pool.submit(() -> exchange(grant.exchange()));
            responses.add(a.get(30, TimeUnit.SECONDS));
            responses.add(b.get(30, TimeUnit.SECONDS));
        }
        assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(200, 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_device WHERE user_id = ?", Integer.class, user.id())).isEqualTo(1);
    }

    @Test
    void tokenRequestsAreLimitedPerIpAndMalformedBodiesAreRejected() throws Exception {
        var notJson = http.send(HttpRequest.newBuilder(uri("/api/connect/token")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertError(notJson, 415, "CONNECT_REQUEST_REJECTED");
        assertError(postRaw("/api/connect/token", "{\"v\":1,\"v\":1}", null), 400, "CONNECT_REQUEST_REJECTED");
        assertError(postRaw("/api/connect/token", "[]", null), 400, "CONNECT_REQUEST_REJECTED");
        assertError(postRaw("/api/connect/token", "{\"a\":\"" + "x".repeat(24_001) + "\"}", null), 413, "CONNECT_REQUEST_REJECTED");
        // 上面 4 次也计入每分钟 20 次的额度。
        for (int i = 4; i < 20; i++) assertError(postRaw("/api/connect/token", "{}", null), 400, "INVALID_GRANT");
        assertError(postRaw("/api/connect/token", "{}", null), 429, "CONNECT_REQUEST_REJECTED");
    }

    // ------------------------------------------------------------------ 第 4、5、7 节：设备令牌

    @Test
    void meReturnsTheContractShapeWithoutContactDetails() throws Exception {
        String token = token(user, "web");
        JsonNode me = json(device("GET", "/api/connect/me", token, null), 200);
        assertThat(fields(me)).containsExactly("user", "device", "ai");
        assertThat(fields(me.path("user"))).containsExactly("name");
        assertThat(fields(me.path("device"))).containsExactly("id", "name", "client", "created");
        assertThat(fields(me.path("ai"))).containsExactly("enabled", "model", "vision", "balance");
        assertThat(me.path("ai").path("enabled").asBoolean()).isFalse();
        assertThat(me.path("ai").path("balance").isIntegralNumber()).isTrue();
        assertThat(me.path("ai").path("balance").asLong()).isEqualTo(10_000);
        assertThat(me.toString()).doesNotContain("@example.test");
        addKey("ok");
        assertThat(json(device("GET", "/api/connect/me", token, null), 200).path("ai").path("enabled").asBoolean()).isTrue();
        // 欠费时旧客户端看到 0（旧契约的余额是非负整数）。
        balances.adjust(admin.id(), user.id(), -20_000, "测试欠费");
        assertThat(json(device("GET", "/api/connect/me", token, null), 200).path("ai").path("balance").asLong()).isZero();
    }

    @Test
    void invalidTokensAndCookiesAreRejectedOnDeviceEndpoints() throws Exception {
        for (String bad : List.of("", "Bearer", "Bearer qfd_short", "Basic abc", "Bearer qfd_" + "A".repeat(43))) {
            var request = HttpRequest.newBuilder(uri("/api/connect/me")).GET();
            if (!bad.isEmpty()) request.header("Authorization", bad);
            assertError(http.send(request.build(), HttpResponse.BodyHandlers.ofString()), 401, "DEVICE_TOKEN_INVALID");
        }
        // 浏览器会话不能调用设备接口。
        assertError(get("/api/connect/me", user.cookie(), null), 401, "DEVICE_TOKEN_INVALID");
        assertError(send("POST", "/api/connect/logout", user.cookie(), null, ORIGIN, null), 401, "DEVICE_TOKEN_INVALID");
        assertError(send("POST", "/api/connect/redeem", user.cookie(), null, ORIGIN, "{\"code\":\"x\"}"), 401, "DEVICE_TOKEN_INVALID");
        assertError(send("POST", "/api/ai/v1/chat/completions", user.cookie(), null, ORIGIN, completion()), 401, "DEVICE_TOKEN_INVALID");
    }

    @Test
    void webEndpointsDoNotAcceptDeviceTokensAndDeviceEndpointsSkipTheOriginCheck() throws Exception {
        String token = token(user, "web");
        // 设备令牌不能调用网页接口。
        assertThat(get("/api/me", null, token).statusCode()).isEqualTo(401);
        assertThat(get("/api/account/ai", null, token).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/account/ai/redeem", null, token, ORIGIN, "{\"code\":\"x\"}").statusCode()).isEqualTo(401);
        assertError(get("/api/connect/devices", null, token), 401, "CONNECT_REQUEST_REJECTED");
        assertError(send("DELETE", "/api/connect/devices/x", null, token, ORIGIN, null), 401, "CONNECT_REQUEST_REJECTED");
        // 设备写接口不带 Origin 也能调用（客户端不发送）；网页写接口仍然校验。
        assertError(device("POST", "/api/connect/redeem", token, "{\"code\":\"bad\"}"), 400, "REDEEM_CODE_INVALID");
        assertThat(send("POST", "/api/account/ai/redeem", user.cookie(), null, null, "{\"code\":\"x\"}").statusCode()).isEqualTo(403);
    }

    @Test
    void logoutRevokesOnlyTheCurrentDevice() throws Exception {
        String first = token(user, "web");
        String second = token(user, "android");
        var logout = device("POST", "/api/connect/logout", first, null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertError(device("GET", "/api/connect/me", first, null), 401, "DEVICE_TOKEN_INVALID");
        assertError(device("POST", "/api/connect/logout", first, null), 401, "DEVICE_TOKEN_INVALID");
        json(device("GET", "/api/connect/me", second, null), 200);
    }

    @Test
    void blockingAUserInvalidatesDevicesImmediately() throws Exception {
        String token = token(user, "web");
        Grant unused = grant(user, "web", webRedirect(), verifier(), "电脑");
        admins.setBlocked(admin.id(), user.id(), true, "测试封禁");
        assertError(device("GET", "/api/connect/me", token, null), 401, "DEVICE_TOKEN_INVALID");
        assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 401, "DEVICE_TOKEN_INVALID");
        assertError(exchange(unused.exchange()), 400, "INVALID_GRANT");
        // 解封后设备不恢复，需要重新授权。
        admins.setBlocked(admin.id(), user.id(), false, "测试解封");
        assertError(device("GET", "/api/connect/me", token, null), 401, "DEVICE_TOKEN_INVALID");

        // 即使撤销还没有写入（例如直接写入封禁记录），认证时也会检查封禁。
        User other = user();
        String live = token(other, "web");
        jdbc.update("INSERT INTO account_block (user_id, actor_id, reason, created_at) VALUES (?, ?, '测试', ?)",
                other.id(), admin.id(), java.sql.Timestamp.from(clock.now));
        assertError(device("GET", "/api/connect/me", live, null), 401, "DEVICE_TOKEN_INVALID");
    }

    @Test
    void devicesUnusedFor180DaysExpireAndRecentUseKeepsThemAlive() throws Exception {
        String token = token(user, "web");
        clock.now = clock.now.plus(Duration.ofDays(179));
        json(device("GET", "/api/connect/me", token, null), 200);
        clock.now = clock.now.plus(Duration.ofDays(179));
        json(device("GET", "/api/connect/me", token, null), 200);
        clock.now = clock.now.plus(Duration.ofDays(181));
        assertError(device("GET", "/api/connect/me", token, null), 401, "DEVICE_TOKEN_INVALID");
    }

    // ------------------------------------------------------------------ 第 8 节：账号页设备列表

    @Test
    void accountPageListsRenamesAndRevokesOnlyOwnDevices() throws Exception {
        String token = token(user, "web");
        JsonNode list = json(get("/api/connect/devices", user.cookie(), null), 200);
        assertThat(fields(list)).containsExactly("devices");
        JsonNode item = list.path("devices").get(0);
        assertThat(fields(item)).containsExactly("id", "name", "client", "created", "lastUsed");
        String id = item.path("id").asString();

        var renamed = send("PUT", "/api/connect/devices/" + id, user.cookie(), null, ORIGIN, "{\"name\":\"\\u0007新名字 \"}");
        assertThat(json(renamed, 200).path("device").path("name").asString()).isEqualTo("新名字");
        assertError(send("PUT", "/api/connect/devices/" + id, user.cookie(), null, ORIGIN, "{\"name\":\"  \"}"), 400, "CONNECT_REQUEST_REJECTED");
        // 别人的设备：不存在。
        assertError(send("PUT", "/api/connect/devices/" + id, admin.cookie(), null, ORIGIN, "{\"name\":\"x\"}"), 404, "CONNECT_REQUEST_REJECTED");
        assertError(send("DELETE", "/api/connect/devices/" + id, admin.cookie(), null, ORIGIN, null), 404, "CONNECT_REQUEST_REJECTED");
        json(device("GET", "/api/connect/me", token, null), 200);

        assertThat(json(send("DELETE", "/api/connect/devices/" + id, user.cookie(), null, ORIGIN, null), 200).path("ok").asBoolean()).isTrue();
        assertError(device("GET", "/api/connect/me", token, null), 401, "DEVICE_TOKEN_INVALID");
        assertThat(json(get("/api/connect/devices", user.cookie(), null), 200).path("devices")).isEmpty();
        assertError(send("DELETE", "/api/connect/devices/" + id, user.cookie(), null, ORIGIN, null), 404, "CONNECT_REQUEST_REJECTED");
    }

    @Test
    void atMostTenDevicesStayConnectedAndTheLeastRecentlyUsedIsRevoked() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            clock.now = clock.now.plusSeconds(30);
            tokens.add(token(user, "web"));
        }
        assertThat(json(get("/api/connect/devices", user.cookie(), null), 200).path("devices")).hasSize(10);
        assertError(device("GET", "/api/connect/me", tokens.getFirst(), null), 401, "DEVICE_TOKEN_INVALID");
        json(device("GET", "/api/connect/me", tokens.getLast(), null), 200);
    }

    // ------------------------------------------------------------------ 第 6 节：兑换

    @Test
    void redeemSharesTheCodeServiceLockAndSingleUseWithTheWeb() throws Exception {
        String token = token(user, "web");
        String code = codes.create(admin.id(), 1, 50_000, 30, "测试").getFirst().code();
        JsonNode redeemed = json(device("POST", "/api/connect/redeem", token, "{\"code\":\" " + code.toLowerCase() + " \"}"), 200);
        assertThat(redeemed.path("added").asLong()).isEqualTo(50_000);
        assertThat(redeemed.path("balance").asLong()).isEqualTo(60_000);
        // 同一个码不能再兑换（网页也一样）。
        assertError(device("POST", "/api/connect/redeem", token, "{\"code\":\"" + code + "\"}"), 400, "REDEEM_CODE_INVALID");
        assertThat(send("POST", "/api/account/ai/redeem", user.cookie(), null, ORIGIN, "{\"code\":\"" + code + "\"}").statusCode()).isEqualTo(400);
        // 累计已失败 2 次；客户端和网页共用锁定计数，第 10 次之后锁定。缺少 code 也算一次失败。
        assertError(device("POST", "/api/connect/redeem", token, "{}"), 400, "REDEEM_CODE_INVALID");
        for (int i = 3; i < 10; i++) {
            clock.now = clock.now.plusSeconds(10); // 不触发每分钟 10 次的限频。
            assertError(device("POST", "/api/connect/redeem", token, "{\"code\":\"AI-0000-0000-0000-000" + i + "\"}"), 400, "REDEEM_CODE_INVALID");
        }
        assertError(device("POST", "/api/connect/redeem", token, "{\"code\":\"bad\"}"), 429, "REDEEM_LOCKED");
        assertThat(send("POST", "/api/account/ai/redeem", user.cookie(), null, ORIGIN, "{\"code\":\"bad\"}").statusCode()).isEqualTo(429);
        // 1 小时后恢复。
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertError(device("POST", "/api/connect/redeem", token, "{\"code\":\"bad\"}"), 400, "REDEEM_CODE_INVALID");
    }

    @Test
    void redeemRequestsAreLimitedPerUser() throws Exception {
        String token = token(user, "web");
        for (int i = 0; i < 10; i++) assertThat(device("POST", "/api/connect/redeem", token, "{\"code\":\"bad\"}").statusCode()).isBetween(400, 429);
        assertError(device("POST", "/api/connect/redeem", token, "{\"code\":\"bad\"}"), 429, "CONNECT_REQUEST_REJECTED");
    }

    // ------------------------------------------------------------------ 第 9 节：AI 网关

    @Test
    void completionReturnsTheOpenAiShapeAndChargesByUsage() throws Exception {
        String token = token(user, "web");
        addKey("ok");
        var response = device("POST", "/api/ai/v1/chat/completions", token, completion("\"stream\":false,\"max_tokens\":100,\"temperature\":0.2,"
                + "\"response_format\":{\"type\":\"json_object\"}"));
        JsonNode body = json(response, 200);
        assertThat(fields(body)).containsExactly("id", "object", "model", "choices", "usage", "quizforge");
        assertThat(body.path("id").asString()).matches("[A-Za-z0-9._:-]{0,200}");
        assertThat(body.path("object").asString()).isEqualTo("chat.completion");
        assertThat(body.path("model").asString()).isEqualTo("quizforge-official");
        JsonNode choice = body.path("choices").get(0);
        assertThat(choice.path("message").path("role").asString()).isEqualTo("assistant");
        assertThat(choice.path("message").path("content").asString()).isEqualTo("OK");
        assertThat(choice.path("finish_reason").asString()).isEqualTo("stop");
        assertThat(body.path("usage").path("prompt_tokens").asInt()).isEqualTo(FakeUpstream.PROMPT_TOKENS);
        assertThat(body.path("usage").path("completion_tokens").asInt()).isEqualTo(FakeUpstream.COMPLETION_TOKENS);
        assertThat(body.path("usage").path("total_tokens").asInt()).isEqualTo(FakeUpstream.PROMPT_TOKENS + FakeUpstream.COMPLETION_TOKENS);
        // 单价 30000 / 90000：ceil((1000 × 30000 + 500 × 90000) / 1000000) = 75。
        assertThat(body.path("quizforge").path("cost").asLong()).isEqualTo(75);
        assertThat(body.path("quizforge").path("balance").asLong()).isEqualTo(9925);
        assertThat(balances.balance(user.id()).balance()).isEqualTo(9925);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_ledger WHERE user_id = ? AND kind = 'ai_call'", Integer.class, user.id())).isEqualTo(1);
        // 上游收到的是配置的模型与客户端的参数，不是 quizforge-official。
        JsonNode sent = JSON.readTree(UPSTREAM.requests.getLast().body());
        assertThat(sent.path("model").asString()).isEqualTo("fake-model");
        assertThat(sent.path("max_tokens").asInt() + sent.path("max_completion_tokens").asInt()).isEqualTo(100);
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(sent.path("stream").asBoolean(false)).isFalse();
    }

    @Test
    void imagesInDataUrlsAreForwarded() throws Exception {
        String token = token(user, "web");
        addKey("ok");
        String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[300]);
        json(device("POST", "/api/ai/v1/chat/completions", token, images(image, 1)), 200);
        assertThat(UPSTREAM.requests.getLast().body()).contains("data:image/png;base64,");
    }

    @Test
    void theWhitelistRejectsEverythingOutsideTheContractBeforeCallingTheModel() throws Exception {
        String token = token(user, "web");
        String key = addKey("ok");
        for (String body : List.of(
                "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}",
                completion("\"stream\":true"),
                completion("\"tools\":[]"),
                completion("\"n\":2"),
                completion("\"max_tokens\":8193"),
                completion("\"max_tokens\":1.5"),
                completion("\"temperature\":3"),
                completion("\"response_format\":{\"type\":\"xml\"}"),
                "{\"model\":\"quizforge-official\",\"messages\":[]}",
                "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}",
                "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"user\",\"content\":\"x\",\"name\":\"a\"}]}",
                "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"user\",\"content\":[]}]}",
                images("https://untrusted.example/image.png", 1),
                images("data:image/gif;base64,AAAA", 1),
                "{\"model\":\"quizforge-official\",\"model\":\"quizforge-official\",\"messages\":[]}",
                "不是 JSON", "[]")) {
            assertError(device("POST", "/api/ai/v1/chat/completions", token, body), 400, "AI_REQUEST_UNSUPPORTED");
        }
        var plain = http.send(HttpRequest.newBuilder(uri("/api/ai/v1/chat/completions")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(completion())).build(), HttpResponse.BodyHandlers.ofString());
        assertError(plain, 415, "AI_REQUEST_UNSUPPORTED");
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    @Test
    void imageAndBodySizeLimitsFollowTheContract() throws Exception {
        String token = token(user, "web");
        String key = addKey("ok");
        // 单张 4 MiB 通过校验，再多 1 字节拒绝。
        String limit = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[4 * 1024 * 1024 + 1]);
        assertError(device("POST", "/api/ai/v1/chat/completions", token, images(limit, 1)), 413, "AI_REQUEST_TOO_LARGE");
        // 合计超过 12 MiB：base64 编码后一定超过 13 MiB 的请求体上限，所以直接校验请求内容（与旧平台相同的两层检查）。
        String full = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[3 * 1024 * 1024]);
        var parts = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5; i++) parts.add(Map.of("type", "image_url", "image_url", Map.of("url", full)));
        Map<String, Object> many = Map.of("model", "quizforge-official", "messages", List.of(Map.of("role", "user", "content", parts)));
        assertThatThrownBy(() -> ClientAiService.request(many)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status().value()).isEqualTo(413);
            assertThat(error.code()).isEqualTo("AI_REQUEST_TOO_LARGE");
        });
        // 请求体超过 13 MiB。
        assertError(device("POST", "/api/ai/v1/chat/completions", token,
                "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(13 * 1024 * 1024) + "\"}]}"),
                413, "AI_REQUEST_TOO_LARGE");
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    @Test
    void anEmptyBalanceIsRefusedWithoutCallingTheModel() throws Exception {
        String token = token(user, "web");
        String key = addKey("ok");
        balances.adjust(admin.id(), user.id(), -10_000, "测试清零");
        assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 402, "AI_BALANCE_EXHAUSTED");
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    @Test
    void upstreamFailuresMapToTheLegacyCodesAndAreNotCharged() throws Exception {
        String token = token(user, "web");
        // 没有可用的 Key。
        assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 503, "AI_DISABLED");
        // 上游被限流。
        addKey("429");
        assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 429, "AI_BUSY");
        jdbc.update("DELETE FROM ai_upstream_key");
        // 上游出错。
        addKey("500");
        assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 502, "AI_UPSTREAM_FAILED");
        jdbc.update("DELETE FROM ai_upstream_key");
        // Key 全部无效。
        addKey("401");
        assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 503, "AI_DISABLED");
        assertThat(balances.balance(user.id()).balance()).isEqualTo(10_000);
    }

    @Test
    void eachUserRunsAtMostTwoRequestsAtOnce() throws Exception {
        String token = token(user, "web");
        addKey("hold");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> device("POST", "/api/ai/v1/chat/completions", token, completion()));
            var second = pool.submit(() -> device("POST", "/api/ai/v1/chat/completions", token, completion()));
            assertThat(UPSTREAM.holdArrived.tryAcquire(2, 20, TimeUnit.SECONDS)).isTrue();
            assertError(device("POST", "/api/ai/v1/chat/completions", token, completion()), 429, "AI_BUSY");
            // 其他用户不受影响。
            String other = token(admin, "web");
            var third = pool.submit(() -> device("POST", "/api/ai/v1/chat/completions", other, completion()));
            assertThat(UPSTREAM.holdArrived.tryAcquire(20, TimeUnit.SECONDS)).isTrue();
            UPSTREAM.holdRelease.countDown();
            json(first.get(30, TimeUnit.SECONDS), 200);
            json(second.get(30, TimeUnit.SECONDS), 200);
            json(third.get(30, TimeUnit.SECONDS), 200);
        }
    }

    // ------------------------------------------------------------------ 第 10–12 节：固定版本导入

    @Test
    void manifestIsAnonymousReadOnlyAndHasTheContractShape() throws Exception {
        byte[] bytes = {0x50, 0x4b, 3, 4, 1, 2, 3, 4};
        String pack = pack("中文 题库.qbank", bytes);
        var response = get("/api/packs/" + pack + "/local-import?revision=1", null, null);
        JsonNode body = json(response, 200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(fields(body)).containsExactly("schemaVersion", "pack", "archive");
        assertThat(body.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fields(body.path("pack"))).containsExactly("id", "revision", "title");
        assertThat(body.path("pack").path("id").asString()).isEqualTo(pack);
        assertThat(body.path("pack").path("revision").isIntegralNumber()).isTrue();
        assertThat(fields(body.path("archive"))).containsExactly("name", "size", "sha256");
        assertThat(body.path("archive").path("name").asString()).isEqualTo("中文 题库.qbank");
        assertThat(body.path("archive").path("size").asLong()).isEqualTo(bytes.length);
        assertThat(body.path("archive").path("sha256").asString()).isEqualTo(HexFormat.of().formatHex(sha256(bytes)));
        // 清单不增加下载数，也不扣流量。
        assertThat(downloads(pack)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_download_traffic", Integer.class)).isZero();
    }

    @Test
    void pinnedDownloadHandsTheExactBlobToNginxAndCountsIt() throws Exception {
        byte[] bytes = {0x50, 0x4b, 3, 4, 9, 8, 7, 6};
        String pack = pack("中文 题库.qbank", bytes);
        String sha = HexFormat.of().formatHex(sha256(bytes));
        var response = get("/api/packs/" + pack + "/download?revision=1&sha256=" + sha, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/octet-stream");
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(response.headers().firstValue("X-Accel-Redirect").orElse(""))
                .isEqualTo("/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .isEqualTo("attachment; filename=\"question-bank.qbank\"; filename*=UTF-8''%E4%B8%AD%E6%96%87%20%E9%A2%98%E5%BA%93.qbank");
        assertThat(downloads(pack)).isEqualTo(1);
    }

    @Test
    void importParametersAreStrictAndErrorsUseTheLocalImportShape() throws Exception {
        byte[] bytes = {1, 2, 3};
        String pack = pack("a.qbank", bytes);
        String sha = HexFormat.of().formatHex(sha256(bytes));
        for (String query : List.of("", "?revision=0", "?revision=1&revision=1", "?revision=1&extra=1", "?revision=%31", "?revision=99999999999999999",
                "?revision=9007199254740992", "?revision=abc")) {
            assertImportError(get("/api/packs/" + pack + "/local-import" + query, null, null), 400, "LOCAL_IMPORT_REQUEST_INVALID");
        }
        for (String query : List.of("?revision=1", "?revision=1&sha256=" + sha.toUpperCase(), "?revision=1&sha256=abc", "?sha256=" + sha)) {
            assertImportError(get("/api/packs/" + pack + "/download" + query, null, null), 400, "LOCAL_IMPORT_REQUEST_INVALID");
        }
        assertImportError(get("/api/packs/" + pack.toUpperCase() + "/local-import?revision=1", null, null), 400, "LOCAL_IMPORT_REQUEST_INVALID");
        assertImportError(send("POST", "/api/packs/" + pack + "/local-import?revision=1", null, null, null, "{}"), 405, "LOCAL_IMPORT_REQUEST_INVALID");
        // 版本或 SHA-256 与当前不符：不自动换版本。
        assertImportError(get("/api/packs/" + pack + "/local-import?revision=2", null, null), 409, "LOCAL_IMPORT_REVISION_CHANGED");
        assertImportError(get("/api/packs/" + pack + "/download?revision=2&sha256=" + sha, null, null), 409, "LOCAL_IMPORT_REVISION_CHANGED");
        assertImportError(get("/api/packs/" + pack + "/download?revision=1&sha256=" + "0".repeat(64), null, null), 409, "LOCAL_IMPORT_REVISION_CHANGED");
        // 不存在、未公开或已下架：对登录用户和作者也一样。
        assertImportError(get("/api/packs/" + Ids.uuidV7() + "/local-import?revision=1", null, null), 404, "LOCAL_IMPORT_UNAVAILABLE");
        jdbc.update("UPDATE catalog_pack SET visibility = 'private' WHERE id = ?", pack);
        assertImportError(get("/api/packs/" + pack + "/local-import?revision=1", user.cookie(), null), 404, "LOCAL_IMPORT_UNAVAILABLE");
        assertImportError(get("/api/packs/" + pack + "/download?revision=1&sha256=" + sha, null, null), 404, "LOCAL_IMPORT_UNAVAILABLE");
        // 文件名不是 .qbank；文件超过 64 MiB。
        String other = pack("a.zip", bytes);
        assertImportError(get("/api/packs/" + other + "/local-import?revision=1", null, null), 422, "LOCAL_IMPORT_FORMAT_UNSUPPORTED");
        String large = pack("large.qbank", new byte[] {4});
        jdbc.update("UPDATE catalog_pack_revision SET size = ? WHERE pack_id = ?", 64L * 1024 * 1024 + 1, large);
        assertImportError(get("/api/packs/" + large + "/local-import?revision=1", null, null), 413, "LOCAL_IMPORT_TOO_LARGE");
        assertThat(downloads(pack)).isZero();
    }

    @Test
    void downloadBudgetsAndRateLimitsFailBeforeCounting() throws Exception {
        // 测试配置的每 IP 每天预算是 1000 字节。
        byte[] bytes = new byte[600];
        String pack = pack("a.qbank", bytes);
        String path = "/api/packs/" + pack + "/download?revision=1&sha256=" + HexFormat.of().formatHex(sha256(bytes));
        assertThat(get(path, null, null).statusCode()).isEqualTo(200);
        assertImportError(get(path, null, null), 429, "LOCAL_IMPORT_TRAFFIC_LIMIT");
        assertThat(downloads(pack)).isEqualTo(1);
        // 站点每月预算优先，失败时 IP 的计数回滚。
        jdbc.update("DELETE FROM client_download_traffic");
        jdbc.update("INSERT INTO client_download_traffic (period, bytes, updated_at) VALUES (?, 1000000, NOW(3))",
                "month:" + clock.now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString().substring(0, 7));
        assertImportError(get(path, null, null), 429, "LOCAL_IMPORT_TRAFFIC_LIMIT");
        assertThat(downloads(pack)).isEqualTo(1);

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        for (int i = 0; i < 60; i++) assertThat(get("/api/packs/" + pack + "/local-import?revision=1", null, null).statusCode()).isEqualTo(200);
        assertImportError(get("/api/packs/" + pack + "/local-import?revision=1", null, null), 429, "LOCAL_IMPORT_RATE_LIMITED");
    }

    // ------------------------------------------------------------------ 辅助

    private User user() {
        var registered = auth.register(Ids.uuidV7() + "@example.test", "客户端用户", "password-123", null);
        return new User(registered.userId(), "__Host-qf_session=" + registered.token());
    }

    private String addKey(String mode) {
        String key = "sk-fake-" + mode + "-" + Ids.uuidV7();
        keys.add(admin.id(), key, "本机假上游", 30_000, 90_000, null);
        return key;
    }

    private String pack(String filename, byte[] bytes) throws Exception {
        String id = Ids.uuidV7();
        Instant now = clock.instant();
        catalog.insertPack(id, admin.id(), now);
        catalog.insertRevision(new CatalogMapper.RevisionRow(id, 1, "基础题型", "简介", "说明", "其他", "[]", "", "upload",
                filename, sha256(bytes), bytes.length, "[]", "approved", null, null, null, null, admin.id(), now));
        jdbc.update("UPDATE catalog_pack SET approved_revision = 1, visibility = 'public', status = 'active' WHERE id = ?", id);
        jdbc.update("UPDATE catalog_pack_revision SET validation_status = 'validated', review_status = 'approved' WHERE pack_id = ?", id);
        return id;
    }

    private long downloads(String pack) {
        return jdbc.queryForObject("SELECT download_count FROM catalog_pack WHERE id = ?", Long.class, pack);
    }

    private static String webRedirect() {
        return "http://127.0.0.1:8787/hub/connect/callback";
    }

    private static String verifier() {
        byte[] data = new byte[48];
        new java.security.SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static Map<String, String> authorizeParams(String client, String redirect, String verifier, String name) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("v", "1");
        params.put("client", client);
        params.put("redirect_uri", redirect);
        params.put("state", verifier().substring(0, 32));
        params.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)));
        params.put("code_challenge_method", "S256");
        params.put("device_name", name);
        return params;
    }

    private static String query(Map<String, String> params) {
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> pairs.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", pairs);
    }

    private String consentKey(User owner, Map<String, String> params) throws Exception {
        var page = get("/connect/authorize?" + query(params), owner.cookie(), null);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        var match = Pattern.compile("data-request=\"([a-f0-9]{64})\"").matcher(page.body());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private HttpResponse<String> decide(User owner, String key, String decision) throws Exception {
        return send("POST", "/api/connect/authorize", owner == null ? null : owner.cookie(), null, ORIGIN,
                "{\"request\":\"" + key + "\",\"decision\":\"" + decision + "\"}");
    }

    private Grant grant(User owner, String client, String redirect, String verifier, String name) throws Exception {
        var params = authorizeParams(client, redirect, verifier, name);
        String key = consentKey(owner, params);
        String target = json(decide(owner, key, "allow"), 200).path("redirect").asString();
        String code = target.substring(target.indexOf("code=") + 5, target.indexOf("&state="));
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(target).endsWith("&state=" + params.get("state"));
        ObjectNode exchange = JSON.createObjectNode().put("v", 1).put("client", client).put("code", code)
                .put("code_verifier", verifier).put("redirect_uri", redirect);
        return new Grant(exchange, target);
    }

    private HttpResponse<String> exchange(JsonNode body) throws Exception {
        return postRaw("/api/connect/token", JSON.writeValueAsString(body), null);
    }

    private String token(User owner, String client) throws Exception {
        String redirect = client.equals("web") ? webRedirect() : "quizforge://hub/connect";
        return json(exchange(grant(owner, client, redirect, verifier(), "设备").exchange()), 200).path("token").asString();
    }

    private static String completion() {
        return "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"system\",\"content\":\"规则\"},{\"role\":\"user\",\"content\":\"你好\"}]}";
    }

    private static String completion(String extra) {
        return "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]," + extra + "}";
    }

    private static String images(String url, int count) {
        StringBuilder parts = new StringBuilder("{\"type\":\"text\",\"text\":\"看图\"}");
        for (int i = 0; i < count; i++) parts.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"").append(url).append("\",\"detail\":\"auto\"}}");
        return "{\"model\":\"quizforge-official\",\"messages\":[{\"role\":\"user\",\"content\":[" + parts + "]}]}";
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, String cookie, String token) throws Exception {
        return send("GET", path, cookie, token, null, null);
    }

    private HttpResponse<String> device(String method, String path, String token, String body) throws Exception {
        return send(method, path, null, token, null, body);
    }

    private HttpResponse<String> postRaw(String path, String body, String token) throws Exception {
        return send("POST", path, null, token, null, body);
    }

    private HttpResponse<String> send(String method, String path, String cookie, String token, String origin, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60));
        if (cookie != null) request.header("Cookie", cookie);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    /** 设备与 AI 接口的错误格式。 */
    private static void assertError(HttpResponse<String> response, int status, String code) {
        JsonNode body = json(response, status);
        assertThat(body.path("error").path("code").asString()).as(response.body()).isEqualTo(code);
        assertThat(body.path("error").path("message").isString()).isTrue();
    }

    /** 固定版本导入的错误格式：error 是字符串，code 在顶层。 */
    private static void assertImportError(HttpResponse<String> response, int status, String code) {
        JsonNode body = json(response, status);
        assertThat(body.path("code").asString()).as(response.body()).isEqualTo(code);
        assertThat(body.path("error").isString()).as(response.body()).isTrue();
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
