package cloud.treenewbee.quizforge.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.identity.application.AuthService.AuthResult;
import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ErrorResponse;
import tools.jackson.databind.json.JsonMapper;

// HTTP 处理线程需要读取已提交的数据，不能依赖测试线程的事务回滚。
@TestPropertySource(properties = "qf.security.allowed-origin=" + SecurityIntegrationTest.ALLOWED_ORIGIN)
class SecurityIntegrationTest extends MySqlTestBase {

    static final String ALLOWED_ORIGIN = "https://quizforge.example.test:8443";
    private static final String PROTECTED_PATH = "/api/security-test/does-not-exist";
    private static final String LOGOUT_PATH = "/api/auth/logout";
    private static final String COOKIE_NAME = "__Host-qf_session";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;

    @Value("${qf.security.allowed-origin}")
    String allowedOrigin;

    @Autowired AuthService auth;
    @Autowired SessionMapper sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ApplicationContext context;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("securityFilterChain")
    SecurityFilterChain securityChain;

    private final List<String> userIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @AfterEach
    void cleanUpCommittedData() {
        for (String userId : userIds) {
            sessions.deleteByUserId(userId);
            jdbc.update("DELETE FROM account_block WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM account_user WHERE id = ?", userId);
        }
        http.close();
    }

    @Test
    void validSessionCookieAuthenticatesRequest() throws Exception {
        AuthResult registered = register();

        assertError(get(PROTECTED_PATH, cookie(registered.token())), 404, "NOT_FOUND", "资源不存在");
    }

    @Test
    void missingCookieReturnsUnifiedUnauthenticatedError() throws Exception {
        assertUnauthenticated(get(PROTECTED_PATH, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed-token", "", "too-short"})
    void malformedTokenIsUnauthenticated(String token) throws Exception {
        assertUnauthenticated(get(PROTECTED_PATH, cookie(token)));
    }

    @Test
    void differentCookieNameIsUnauthenticated() throws Exception {
        AuthResult registered = register();

        assertUnauthenticated(get(PROTECTED_PATH, "qf_session=" + registered.token()));
    }

    @Test
    void unknownWellFormedTokenIsUnauthenticated() throws Exception {
        assertUnauthenticated(get(PROTECTED_PATH, cookie(SessionToken.generate())));
    }

    @ParameterizedTest
    @CsvSource({"-60, 3600", "3600, -60"})
    void idleOrAbsoluteExpiredSessionIsUnauthenticated(long idleOffset, long absoluteOffset) throws Exception {
        AuthResult owner = register();
        Instant now = clock.instant();
        String token = SessionToken.generate();
        sessions.insert(new SessionRow(SessionToken.hash(token), owner.userId(), now.minusSeconds(120),
                now.minusSeconds(120), now.plusSeconds(idleOffset), now.plusSeconds(absoluteOffset),
                "security-expiry-test"));

        assertUnauthenticated(get(PROTECTED_PATH, cookie(token)));
    }

    @Test
    void blockingUserInvalidatesExistingSessionOnNextRequest() throws Exception {
        AuthResult registered = register();
        String cookie = cookie(registered.token());
        assertError(get(PROTECTED_PATH, cookie), 404, "NOT_FOUND", "资源不存在");

        jdbc.update("INSERT INTO account_block (user_id, actor_id, reason, created_at) VALUES (?, ?, ?, ?)",
                registered.userId(), registered.userId(), "security-test block",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        assertUnauthenticated(get(PROTECTED_PATH, cookie));
    }

    @Test
    void logoutInvalidatesSessionOnNextRequest() throws Exception {
        AuthResult registered = register();
        String cookie = cookie(registered.token());
        assertError(get(PROTECTED_PATH, cookie), 404, "NOT_FOUND", "资源不存在");

        auth.logout(registered.token());

        assertUnauthenticated(get(PROTECTED_PATH, cookie));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "__Host-qf_session=malformed-token",
            "__Host-qf_session=",
            "__Host-qf_session=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "different_cookie=anything"
    })
    void healthAllowsInvalidCookies(String cookie) throws Exception {
        HttpResponse<String> response = get("/api/health", cookie);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"ok\":true", "\"database\":\"up\"");
    }

    @Test
    void matchingConfiguredOriginIsAllowed() throws Exception {
        HttpResponse<String> response = request("POST", LOGOUT_PATH, null, allowedOrigin);

        assertThat(response.statusCode()).isNotEqualTo(403);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "https://other.example.test:8443",
            "https://quizforge.example.test:9443",
            "http://quizforge.example.test:8443",
            ALLOWED_ORIGIN + "/"
    })
    void missingOrDifferentOriginIsRejected(String origin) throws Exception {
        assertOriginRejected(request("POST", LOGOUT_PATH, null, origin));
    }

    @Test
    void getWithoutOriginIsNotRejected() throws Exception {
        assertUnauthenticated(get(PROTECTED_PATH, null));
    }

    @Test
    void rejectedWriteWithValidSessionStillReturnsForbidden() throws Exception {
        AuthResult registered = register();
        assertError(get(PROTECTED_PATH, cookie(registered.token())), 404, "NOT_FOUND", "资源不存在");

        assertOriginRejected(request("POST", LOGOUT_PATH, cookie(registered.token()),
                "https://other.example.test:8443"));
    }

    @Test
    void originFilterPrecedesSessionAuthenticationInSecurityChain() {
        var filters = securityChain.getFilters();
        int originIndex = IntStream.range(0, filters.size())
                .filter(i -> filters.get(i) instanceof OriginCheckFilter).findFirst().orElse(-1);
        int sessionIndex = IntStream.range(0, filters.size())
                .filter(i -> filters.get(i) instanceof SessionAuthenticationFilter).findFirst().orElse(-1);

        assertThat(originIndex).isNotNegative();
        assertThat(sessionIndex).isGreaterThan(originIndex);
    }

    @Test
    void sessionFilterIsNotRegisteredAsServletBean() {
        assertThat(context.getBeansOfType(SessionAuthenticationFilter.class)).isEmpty();
    }

    @Test
    void originFilterIsNotRegisteredAsServletBean() {
        assertThat(context.getBeansOfType(OriginCheckFilter.class)).isEmpty();
    }

    private AuthResult register() {
        AuthResult registered = auth.register("security-" + Ids.uuidV7() + "@example.test",
                "Security Test", "security-test-password", "security-http-test");
        userIds.add(registered.userId());
        return registered;
    }

    private static String cookie(String token) {
        return COOKIE_NAME + "=" + token;
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return request("GET", path, cookie, null);
    }

    private HttpResponse<String> request(String method, String path, String cookie, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertUnauthenticated(HttpResponse<String> response) {
        assertError(response, 401, "UNAUTHENTICATED", "请先登录");
    }

    private static void assertOriginRejected(HttpResponse<String> response) {
        assertError(response, 403, "ORIGIN_REJECTED", "请求来源不受信任");
    }

    private static void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        MediaType contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("json");
        if (status == 401 || status == 403) {
            assertThat(contentType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        }
        assertThat(JSON.readValue(response.body(), ErrorResponse.class)).isEqualTo(ErrorResponse.of(code, message));
    }
}
