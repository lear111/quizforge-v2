package cloud.treenewbee.quizforge.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.identity.domain.PasswordHasher;
import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionRow;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ErrorResponse;
import tools.jackson.databind.json.JsonMapper;

@Import(AuthApiIntegrationTest.FixedClockConfiguration.class)
@TestPropertySource(properties = "qf.security.allowed-origin=" + AuthApiIntegrationTest.ORIGIN)
class AuthApiIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-api.example.test";
    private static final Instant NOW = Instant.parse("2026-10-28T12:34:56.789Z");
    private static final String PASSWORD = "pass12345";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}") int port;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @MockitoSpyBean SessionMapper sessions;

    private final Set<String> emails = new HashSet<>();
    private final Set<String> userIds = new HashSet<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    // HTTP 处理发生在服务器线程，数据须提交并按本测试创建的 ID 清理。
    @AfterEach
    void cleanUpCommittedData() {
        for (String email : emails) {
            users.findByEmail(email).ifPresent(user -> userIds.add(user.id()));
        }
        for (String userId : userIds) {
            sessions.deleteByUserId(userId);
            jdbc.update("DELETE FROM account_block WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM account_user WHERE id = ?", userId);
        }
        http.close();
    }

    @Test
    void registerReturnsPublicUserAndSecureCookie() throws Exception {
        String email = email();
        HttpResponse<String> response = register(email, "  Alice  ", PASSWORD);

        String id = assertUser(response, email, "Alice");
        String cookie = assertSessionCookie(response, false);
        assertThat(users.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.passwordHash()).isNotEqualTo(PASSWORD);
            assertThat(user.passwordAlgo()).isEqualTo("scrypt-v1");
            assertThat(user.createdAt()).isEqualTo(NOW);
        });
        assertThat(sessions.findActive(SessionToken.hash(token(cookie)), NOW)).hasValueSatisfying(session -> {
            assertThat(session.userId()).isEqualTo(id);
            assertThat(session.createdAt()).isEqualTo(NOW);
            assertThat(session.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
            assertThat(session.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        });
    }

    @Test
    void loginReturnsSameUserAndNewSecureCookie() throws Exception {
        String email = email();
        HttpResponse<String> registered = register(email, "Alice", PASSWORD);
        String id = assertUser(registered, email, "Alice");
        String firstCookie = assertSessionCookie(registered, false);

        HttpResponse<String> loggedIn = login("  " + email.toUpperCase(Locale.ROOT) + "  ", PASSWORD);

        assertThat(assertUser(loggedIn, email, "Alice")).isEqualTo(id);
        assertThat(assertSessionCookie(loggedIn, false)).isNotEqualTo(firstCookie);
    }

    @Test
    void registrationNormalizesLegacyUnicodeWhitespace() throws Exception {
        String email = email();
        HttpResponse<String> response = register("\uFEFF\u00A0" + email.toUpperCase(Locale.ROOT) + "\u3000",
                "\u3000王同学\u00A0", PASSWORD);

        assertUser(response, email, "王同学");
    }

    @Test
    void passwordWhitespaceIsPreserved() throws Exception {
        String email = email();
        String password = "  pass12345  ";
        String id = assertUser(register(email, "Alice", password), email, "Alice");

        assertThat(assertUser(login(email, password), email, "Alice")).isEqualTo(id);
        assertError(login(email, password.trim()), 401, "INVALID_CREDENTIALS", "账号或密码不正确，或账号已停用");
    }

    @Test
    void validationAcceptsEmailAndNameAndPasswordUpperBounds() throws Exception {
        String email = "a".repeat(131) + Ids.uuidV7() + "@example.test";
        assertThat(email).hasSize(180);
        emails.add(email);
        String name = "学".repeat(64);
        String password = "p".repeat(128);

        assertUser(register(email, name, password), email, name);
        assertUser(login(email, password), email, name);
    }

    @Test
    void nameLengthUsesDatabaseUnicodeCodePoints() throws Exception {
        String email = email();
        String name = "\uD83D\uDE00".repeat(64);

        assertUser(register(email, name, PASSWORD), email, name);
    }

    @Test
    void passwordLengthCountsUnicodeCharactersWithoutTrimming() throws Exception {
        String email = email();
        String password = "\uD83D\uDE00".repeat(128);
        assertUser(register(email, "Alice", password), email, "Alice");
        assertUser(login(email, password), email, "Alice");
        assertValidation(register(email(), "Alice", "\uD83D\uDE00".repeat(7)));

        String whitespaceEmail = email();
        assertUser(register(whitespaceEmail, "Alice", " ".repeat(8)), whitespaceEmail, "Alice");
        assertUser(login(whitespaceEmail, " ".repeat(8)), whitespaceEmail, "Alice");
    }

    @Test
    void duplicateRegistrationReturnsConflictWithoutCookie() throws Exception {
        String email = email();
        assertUser(register(email, "Alice", PASSWORD), email, "Alice");

        assertError(register(email.toUpperCase(Locale.ROOT), "Other", PASSWORD),
                409, "EMAIL_TAKEN", "该邮箱已注册");
    }

    @Test
    void wrongPasswordAndMissingEmailHaveIdenticalErrorBodies() throws Exception {
        String email = email();
        assertUser(register(email, "Alice", PASSWORD), email, "Alice");

        HttpResponse<String> wrongPassword = login(email, "wrong-password");
        HttpResponse<String> missingEmail = login(email(), PASSWORD);

        assertError(wrongPassword, 401, "INVALID_CREDENTIALS", "账号或密码不正确，或账号已停用");
        assertError(missingEmail, 401, "INVALID_CREDENTIALS", "账号或密码不正确，或账号已停用");
        assertThat(missingEmail.body()).isEqualTo(wrongPassword.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrationBodies")
    void registerRejectsInvalidFields(String scenario, Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("email", email(), "name", "Alice", "password", PASSWORD));
        changes.forEach((key, value) -> {
            if (value == null) {
                body.remove(key);
            } else {
                body.put(key, value);
            }
        });

        assertValidation(post("/api/auth/register", body, null, ORIGIN, null));
    }

    static Stream<Arguments> invalidRegistrationBodies() {
        return Stream.of(
                Arguments.of("empty email", Map.of("email", "")),
                Arguments.of("blank email", Map.of("email", "   ")),
                Arguments.of("Unicode blank email", Map.of("email", "\u00A0\u3000\uFEFF")),
                Arguments.of("long email", Map.of("email", "a".repeat(169) + "@example.test")),
                Arguments.of("bad email", Map.of("email", "missing-at.example.test")),
                Arguments.of("email without dot", Map.of("email", "a@example")),
                Arguments.of("email with inner whitespace", Map.of("email", "a\u00A0b@example.test")),
                Arguments.of("empty name", Map.of("name", "")),
                Arguments.of("blank name", Map.of("name", "  ")),
                Arguments.of("long name", Map.of("name", "学".repeat(65))),
                Arguments.of("long Unicode name", Map.of("name", "\uD83D\uDE00".repeat(65))),
                Arguments.of("short password", Map.of("password", "1234567")),
                Arguments.of("short Unicode password", Map.of("password", "\uD83D\uDE00".repeat(7))),
                Arguments.of("long password", Map.of("password", "p".repeat(129))),
                Arguments.of("missing email", missing("email")),
                Arguments.of("missing name", missing("name")),
                Arguments.of("missing password", missing("password")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-email", "a@example", "a b@example.test"})
    void loginRejectsInvalidEmail(String email) throws Exception {
        assertValidation(login(email, PASSWORD));
    }

    @Test
    void loginRejectsEmptyPassword() throws Exception {
        assertValidation(login(email(), ""));
    }

    @Test
    void legacyAccountWithShortPasswordCanLogIn() throws Exception {
        String email = email();
        String id = Ids.uuidV7();
        userIds.add(id);
        users.insert(new UserRow(id, email, "Legacy", hasher.hash("pass123"), "scrypt-v1",
                false, NOW, NOW, 0L));

        assertThat(assertUser(login(email, "pass123"), email, "Legacy")).isEqualTo(id);
    }

    @Test
    void loginRejectsLongPasswordAndMissingFields() throws Exception {
        assertValidation(login(email(), "p".repeat(1025)));
        assertValidation(login("a".repeat(169) + "@example.test", PASSWORD));
        assertValidation(post("/api/auth/login", Map.of("email", email()), null, ORIGIN, null));
        assertValidation(post("/api/auth/login", Map.of("password", PASSWORD), null, ORIGIN, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/register", "/api/auth/login"})
    void malformedOrNonJsonBodiesReturnValidationError(String path) throws Exception {
        assertValidation(rawPost(path, "{broken-json", "application/json", null, ORIGIN, null));
        assertValidation(rawPost(path, "not json", "text/plain", null, ORIGIN, null));
        assertValidation(rawPost(path, null, null, null, ORIGIN, null));
        assertValidation(rawPost(path, "null", "application/json", null, ORIGIN, null));
        assertValidation(rawPost(path, "{}", "application/json", null, ORIGIN, null));
        assertValidation(rawPost(path, "{\"email\":null,\"name\":null,\"password\":null}",
                "application/json", null, ORIGIN, null));
        assertValidation(rawPost(path, "[]", "application/json", null, ORIGIN, null));
    }

    @Test
    void endToEndRegisterMeLogoutMeIsUnauthenticated() throws Exception {
        String email = email();
        HttpResponse<String> registered = register(email, "Alice", PASSWORD);
        String id = assertUser(registered, email, "Alice");
        String cookie = assertSessionCookie(registered, false);
        assertThat(assertUser(getMe(cookie), email, "Alice")).isEqualTo(id);

        HttpResponse<String> loggedOut = post("/api/auth/logout", null, cookie, ORIGIN, null);

        assertThat(loggedOut.statusCode()).isEqualTo(204);
        assertThat(loggedOut.body()).isEmpty();
        assertSessionCookie(loggedOut, true);
        assertError(getMe(cookie), 401, "UNAUTHENTICATED", "请先登录");
        assertThat(post("/api/auth/logout", null, cookie, ORIGIN, null).statusCode()).isEqualTo(204);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"__Host-qf_session=malformed", "wrong_name=anything",
            "__Host-qf_session=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void logoutWithoutUsableCookieIsIdempotent(String cookie) throws Exception {
        HttpResponse<String> response = post("/api/auth/logout", null, cookie, ORIGIN, null);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertSessionCookie(response, true);
    }

    @Test
    void meWithoutCookieIsUnauthenticated() throws Exception {
        assertError(getMe(null), 401, "UNAUTHENTICATED", "请先登录");
    }

    @Test
    void logoutWithExpiredSessionStillReturnsNoContentAndDeletesIt() throws Exception {
        var fixture = oldSession(301, NOW.minusSeconds(1));

        HttpResponse<String> response = post("/api/auth/logout", null, fixture.cookie(), ORIGIN, null);

        assertThat(response.statusCode()).isEqualTo(204);
        assertSessionCookie(response, true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE token_hash = ?",
                Integer.class, fixture.row().tokenHash())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/register", "/api/auth/login", "/api/auth/logout"})
    void controllersStillRejectMissingAndWrongOrigins(String path) throws Exception {
        Map<String, Object> body = Map.of("email", email(), "name", "Alice", "password", PASSWORD);

        assertError(post(path, body, null, null, null), 403, "ORIGIN_REJECTED", "请求来源不受信任");
        assertError(post(path, body, null, "https://other.example.test", null),
                403, "ORIGIN_REJECTED", "请求来源不受信任");
    }

    @Test
    void userAgentIsTruncatedForRegistrationAndLogin() throws Exception {
        String email = email();
        String userAgent = "agent/" + "x".repeat(500);
        HttpResponse<String> registered = post("/api/auth/register",
                Map.of("email", email, "name", "Alice", "password", PASSWORD), null, ORIGIN, userAgent);
        assertUser(registered, email, "Alice");
        String first = assertSessionCookie(registered, false);
        HttpResponse<String> loggedIn = post("/api/auth/login", Map.of("email", email, "password", PASSWORD),
                null, ORIGIN, userAgent);
        assertUser(loggedIn, email, "Alice");
        String second = assertSessionCookie(loggedIn, false);

        for (String cookie : new String[] {first, second}) {
            assertThat(sessions.findActive(SessionToken.hash(token(cookie)), NOW)).hasValueSatisfying(session ->
                    assertThat(session.userAgent()).isEqualTo(userAgent.substring(0, 255)));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 299, 300})
    void sessionWithinFiveMinutesDoesNotWrite(long secondsSinceLastUse) throws Exception {
        var fixture = oldSession(secondsSinceLastUse, NOW.plus(Duration.ofDays(30)));
        SessionRow before = fixture.row();
        clearInvocations(sessions);

        assertThat(getMe(fixture.cookie()).statusCode()).isEqualTo(200);

        verify(sessions, never()).touch(any(), any(), any());
        assertThat(sessions.findActive(before.tokenHash(), NOW)).hasValueSatisfying(after -> {
            assertThat(after.lastUsedAt()).isEqualTo(before.lastUsedAt());
            assertThat(after.idleExpiresAt()).isEqualTo(before.idleExpiresAt());
        });
    }

    @Test
    void sessionAfterFiveMinutesRenewsIdleExpiryAndThrottlesNextRequest() throws Exception {
        var fixture = oldSession(301, NOW.plus(Duration.ofDays(30)));
        SessionRow before = fixture.row();
        clearInvocations(sessions);

        assertThat(getMe(fixture.cookie()).statusCode()).isEqualTo(200);
        assertThat(getMe(fixture.cookie()).statusCode()).isEqualTo(200);

        verify(sessions, times(1)).touch(any(), any(), any());
        assertThat(sessions.findActive(before.tokenHash(), NOW)).hasValueSatisfying(after -> {
            assertThat(after.lastUsedAt()).isEqualTo(NOW);
            assertThat(after.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
            assertThat(after.absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());
        });
    }

    @Test
    void renewedIdleExpiryCannotExceedAbsoluteExpiry() throws Exception {
        Instant absolute = NOW.plus(Duration.ofDays(2));
        var fixture = oldSession(301, absolute);
        SessionRow before = fixture.row();

        assertThat(getMe(fixture.cookie()).statusCode()).isEqualTo(200);

        assertThat(sessions.findActive(before.tokenHash(), NOW)).hasValueSatisfying(after -> {
            assertThat(after.lastUsedAt()).isEqualTo(NOW);
            assertThat(after.idleExpiresAt()).isEqualTo(absolute);
        });
    }

    @Test
    void blockedUserIsNotAuthenticatedOrRenewed() throws Exception {
        var fixture = oldSession(301, NOW.plus(Duration.ofDays(30)));
        SessionRow before = fixture.row();
        jdbc.update("INSERT INTO account_block (user_id, actor_id, reason, created_at) VALUES (?, ?, ?, ?)",
                before.userId(), before.userId(), "api test block", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        clearInvocations(sessions);

        assertError(getMe(fixture.cookie()), 401, "UNAUTHENTICATED", "请先登录");

        verify(sessions, never()).touch(any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM auth_session WHERE token_hash = ?",
                LocalDateTime.class, before.tokenHash())).isEqualTo(LocalDateTime.ofInstant(before.lastUsedAt(), ZoneOffset.UTC));
    }

    @Test
    void touchExceptionDoesNotFailAuthenticatedRequest() throws Exception {
        var fixture = oldSession(301, NOW.plus(Duration.ofDays(30)));
        SessionRow before = fixture.row();
        doThrow(new DataAccessResourceFailureException("simulated touch failure"))
                .when(sessions).touch(any(), any(), any());

        assertThat(getMe(fixture.cookie()).statusCode()).isEqualTo(200);

        assertThat(sessions.findActive(before.tokenHash(), NOW)).hasValueSatisfying(after ->
                assertThat(after.lastUsedAt()).isEqualTo(before.lastUsedAt()));
    }

    @Test
    void touchReturningZeroDoesNotFailAuthenticatedRequest() throws Exception {
        var fixture = oldSession(301, NOW.plus(Duration.ofDays(30)));
        doReturn(0).when(sessions).touch(any(), any(), any());

        assertThat(getMe(fixture.cookie()).statusCode()).isEqualTo(200);
    }

    private record SessionFixture(SessionRow row, String cookie) {
    }

    private SessionFixture oldSession(long secondsSinceLastUse, Instant absoluteExpiry) {
        var owner = auth.register(email(), "Session Owner", PASSWORD, "fixture");
        userIds.add(owner.userId());
        String token = SessionToken.generate();
        SessionRow session = new SessionRow(SessionToken.hash(token), owner.userId(), NOW.minus(Duration.ofDays(1)),
                NOW.minusSeconds(secondsSinceLastUse), NOW.plus(Duration.ofHours(1)), absoluteExpiry, "fixture");
        sessions.insert(session);
        return new SessionFixture(session, "__Host-qf_session=" + token);
    }

    private String email() {
        String email = "api-" + Ids.uuidV7() + "@example.test";
        emails.add(email);
        return email;
    }

    private static Map<String, Object> missing(String key) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(key, null);
        return value;
    }

    private HttpResponse<String> register(String email, String name, String password) throws Exception {
        return post("/api/auth/register", Map.of("email", email, "name", name, "password", password), null, ORIGIN, null);
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("/api/auth/login", Map.of("email", email, "password", password), null, ORIGIN, null);
    }

    private HttpResponse<String> getMe(String cookie) throws Exception {
        return send("GET", "/api/me", null, null, cookie, null, null);
    }

    private HttpResponse<String> post(String path, Object body, String cookie, String origin, String userAgent) throws Exception {
        return rawPost(path, body == null ? null : JSON.writeValueAsString(body),
                body == null ? null : "application/json", cookie, origin, userAgent);
    }

    private HttpResponse<String> rawPost(String path, String body, String contentType, String cookie,
                                       String origin, String userAgent) throws Exception {
        return send("POST", path, body, contentType, cookie, origin, userAgent);
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType, String cookie,
                                    String origin, String userAgent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) request.header("Content-Type", contentType);
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        if (userAgent != null) request.header("User-Agent", userAgent);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String assertUser(HttpResponse<String> response, String email, String name) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        Map<?, ?> body = JSON.readValue(response.body(), Map.class);
        assertThat(body.keySet()).isEqualTo(Set.of("user"));
        assertThat(body.get("user")).isInstanceOf(Map.class);
        Map<?, ?> user = (Map<?, ?>) body.get("user");
        assertThat(user.keySet()).isEqualTo(Set.of("id", "email", "phone", "name", "admin", "unverifiedContacts"));
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("phone")).isNull();
        assertThat(user.get("admin")).isEqualTo(false);
        assertThat(user.get("name")).isEqualTo(name);
        assertThat(user.get("id")).isInstanceOf(String.class);
        String id = (String) user.get("id");
        assertThat(id).isNotBlank();
        return id;
    }

    private static String assertSessionCookie(HttpResponse<String> response, boolean clearing) {
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(1);
        String[] parts = response.headers().firstValue("Set-Cookie").orElseThrow().split(";");
        String pair = parts[0].trim();
        assertThat(pair).startsWith("__Host-qf_session=");
        String token = token(pair);
        if (clearing) {
            assertThat(token).isEmpty();
        } else {
            assertThat(SessionToken.isWellFormed(token)).isTrue();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String[] attribute = parts[i].trim().split("=", 2);
            attributes.put(attribute[0].toLowerCase(Locale.ROOT), attribute.length == 2 ? attribute[1] : "");
        }
        assertThat(attributes.keySet()).isEqualTo(Set.of("path", "max-age", "expires", "secure", "httponly", "samesite"));
        assertThat(attributes).doesNotContainKey("domain");
        assertThat(attributes.get("path")).isEqualTo("/");
        assertThat(attributes.get("max-age")).isEqualTo(clearing ? "0" : "2592000");
        assertThat(attributes.get("secure")).isEmpty();
        assertThat(attributes.get("httponly")).isEmpty();
        assertThat(attributes.get("samesite")).isEqualTo("Lax");
        Instant expires = ZonedDateTime.parse(attributes.get("expires"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        if (clearing) {
            assertThat(expires).isEqualTo(Instant.EPOCH);
        } else {
            assertThat(expires).isAfter(Instant.EPOCH);
        }
        return pair;
    }

    private static String token(String cookie) {
        return cookie.substring(cookie.indexOf('=') + 1);
    }

    private static void assertValidation(HttpResponse<String> response) {
        assertError(response, 400, "VALIDATION_FAILED", "请求内容不符合要求");
    }

    private static void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        MediaType type = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(type.getType()).isEqualTo("application");
        assertThat(type.getSubtype()).isEqualTo("json");
        assertThat(JSON.readValue(response.body(), ErrorResponse.class)).isEqualTo(ErrorResponse.of(code, message));
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
