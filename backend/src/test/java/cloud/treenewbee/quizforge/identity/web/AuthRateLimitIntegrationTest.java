package cloud.treenewbee.quizforge.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import tools.jackson.databind.json.JsonMapper;

/**
 * 匿名认证接口按客户端 IP 共用每分钟 12 次的额度（D-4）。测试直接连接 Tomcat，
 * 对端是本机（可信代理），因此用 X-Forwarded-For 模拟 Nginx 传来的不同客户端 IP。
 */
@Import(AccountCompletionIntegrationTest.MutableClockConfiguration.class)
@TestPropertySource(properties = {
        "qf.security.allowed-origin=" + AuthRateLimitIntegrationTest.ORIGIN,
        "qf.auth.rate-limit.requests-per-minute=12"})
class AuthRateLimitIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-limits.example.test";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}") int port;
    @Autowired AccountCompletionIntegrationTest.MutableClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void close() {
        http.close();
    }

    @Test
    void anonymousAuthEndpointsShareTwelveRequestsPerMinutePerClientIp() throws Exception {
        clock.set(AccountCompletionIntegrationTest.START);
        String ip = "203.0.113.10";
        String[] paths = {"/api/auth/login", "/api/auth/register", "/api/auth/login-code", "/api/auth/email-code",
                "/api/auth/phone-code", "/api/auth/password-reset-code", "/api/auth/password-reset"};
        for (int i = 0; i < 12; i++) {
            assertThat(post(paths[i % paths.length], ip).statusCode()).isNotEqualTo(429);
        }
        clock.advance(Duration.ofSeconds(20));
        for (String path : paths) {
            HttpResponse<String> limited = post(path, ip);
            assertThat(limited.statusCode()).as(path).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).hasValue("40");
            assertThat(JSON.readTree(limited.body()).path("error").path("code").asString()).isEqualTo("RATE_LIMITED");
        }

        // 其他 IP、非匿名认证接口不受影响。
        assertThat(post("/api/auth/login", "203.0.113.11").statusCode()).isEqualTo(400);
        assertThat(get("/api/config", ip).statusCode()).isEqualTo(200);
        assertThat(post("/api/auth/logout", ip).statusCode()).isEqualTo(204);

        // 窗口滑过后恢复。
        clock.advance(Duration.ofSeconds(41));
        assertThat(post("/api/auth/login", ip).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectedRequestsDoNotReachTheApplication() throws Exception {
        clock.set(AccountCompletionIntegrationTest.START.plus(Duration.ofHours(1)));
        String ip = "203.0.113.20";
        for (int i = 0; i < 12; i++) post("/api/auth/login", ip);
        // 第 13 次即使请求体合法也被拒绝，返回 429 而不是 401。
        HttpResponse<String> response = send("POST", "/api/auth/login", ip,
                "{\"identifier\":\"nobody@example.test\",\"password\":\"whatever-1\"}");
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private HttpResponse<String> post(String path, String ip) throws Exception {
        return send("POST", path, ip, "{}");
    }

    private HttpResponse<String> get(String path, String ip) throws Exception {
        return send("GET", path, ip, null);
    }

    private HttpResponse<String> send(String method, String path, String ip, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", ORIGIN)
                .header("X-Forwarded-For", ip)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
