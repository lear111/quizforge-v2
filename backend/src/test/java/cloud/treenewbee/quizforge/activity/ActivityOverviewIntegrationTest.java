package cloud.treenewbee.quizforge.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.activity.application.ActivityTracker;
import cloud.treenewbee.quizforge.activity.infrastructure.ActivityMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 在线活跃统计（心跳只在内存中计数、定期批量写入）与后台概况。 */
@Import(ActivityOverviewIntegrationTest.ClockConfiguration.class)
@TestPropertySource(properties = {
        "qf.security.allowed-origin=" + ActivityOverviewIntegrationTest.ORIGIN,
        // 由测试显式调用 flush，避免后台定时写入干扰断言。
        "qf.activity.flush-enabled=false"})
class ActivityOverviewIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-activity.example.test";
    static final Instant START = Instant.parse("2026-11-01T04:00:00Z"); // 北京时间 12:00
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityTracker tracker;
    @Autowired SteppingClock clock;
    @MockitoSpyBean ActivityMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        clock.now.set(START);
        tracker.flush();
        jdbc.update("DELETE FROM activity_daily");
        clearInvocations(mapper);
    }

    @AfterEach
    void close() {
        http.close();
    }

    @Test
    void activityRequiresLoginAndAKnownKind() throws Exception {
        assertThat(post("/api/activity", "{\"kind\":\"heartbeat\"}", null).statusCode()).isEqualTo(401);
        Session user = register();
        assertThat(post("/api/activity", "{\"kind\":\"download\"}", user.cookie()).statusCode()).isEqualTo(400);
        assertThat(post("/api/activity", "{}", user.cookie()).statusCode()).isEqualTo(400);
        assertThat(post("/api/activity", "{\"kind\":\"heartbeat\"}", user.cookie()).statusCode()).isEqualTo(204);
    }

    @Test
    void heartbeatsNeverTouchTheDatabase() throws Exception {
        Session user = register();
        for (int i = 0; i < 50; i++) {
            assertThat(post("/api/activity", "{\"kind\":\"heartbeat\"}", user.cookie()).statusCode()).isEqualTo(204);
        }
        verify(mapper, never()).upsert(any());
        assertThat(tracker.flush()).isZero();
        verify(mapper, never()).upsert(any());
        assertThat(count()).isZero();
    }

    @Test
    void visitsAndPracticeAreBatchedAndPracticeOnlyIncreases() throws Exception {
        Session visitor = register();
        Session learner = register();
        post("/api/activity", "{\"kind\":\"visit\"}", visitor.cookie());
        post("/api/activity", "{\"kind\":\"practice\"}", learner.cookie());
        post("/api/activity", "{\"kind\":\"visit\"}", learner.cookie());
        // 请求本身不写数据库，定期汇总时一次写入。
        verify(mapper, never()).upsert(any());
        assertThat(tracker.flush()).isEqualTo(2);
        assertThat(practiced(visitor.id())).isFalse();
        assertThat(practiced(learner.id())).isTrue();

        // 再次访问不会把“练习过”改回去；同一天同一用户只有一行。
        post("/api/activity", "{\"kind\":\"visit\"}", learner.cookie());
        tracker.flush();
        assertThat(practiced(learner.id())).isTrue();
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void failedFlushKeepsPendingActivityForTheNextAttempt() throws Exception {
        Session user = register();
        post("/api/activity", "{\"kind\":\"practice\"}", user.cookie());
        doThrow(new DataAccessResourceFailureException("down")).when(mapper).upsert(any());
        assertThatThrownBy(() -> tracker.flush()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count()).isZero();
        org.mockito.Mockito.reset(mapper);
        assertThat(tracker.flush()).isEqualTo(1);
        assertThat(practiced(user.id())).isTrue();
    }

    @Test
    void overviewIsAdminOnlyAndReportsUsersOnlineStorageAndTrend() throws Exception {
        Session member = register();
        Session admin = register();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", admin.id());
        assertThat(get("/api/admin/overview", member.cookie()).statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/overview", null).statusCode()).isEqualTo(401);

        // 一个 8 天前注册的用户不计入近 7 天。
        jdbc.update("""
                INSERT INTO account_user (id, email, name, password_hash, password_algo, is_admin, created_at, updated_at, version)
                VALUES (?, ?, 'Old', 'x', 'scrypt-v1', FALSE, '2026-10-24 00:00:00.000', '2026-10-24 00:00:00.000', 0)
                """, UUID.randomUUID().toString(), "old-" + System.nanoTime() + "@example.test");
        post("/api/activity", "{\"kind\":\"practice\"}", member.cookie());
        post("/api/activity", "{\"kind\":\"heartbeat\"}", admin.cookie());

        JsonNode overview = JSON.readTree(get("/api/admin/overview", admin.cookie()).body());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM account_user", Long.class);
        long recent = jdbc.queryForObject("SELECT COUNT(*) FROM account_user WHERE created_at >= ?", Long.class,
                "2026-10-25 16:00:00");
        assertThat(overview.path("users").path("total").asLong()).isEqualTo(total);
        assertThat(overview.path("users").path("newLast7Days").asLong()).isEqualTo(recent).isLessThan(total);
        assertThat(overview.path("online").asLong()).isEqualTo(2);
        assertThat(overview.path("storageUsedBytes").asLong()).isEqualTo(
                jdbc.queryForObject("SELECT COALESCE(SUM(storage_used), 0) FROM quota_account", Long.class));
        JsonNode trend = overview.path("trend");
        assertThat(trend.size()).isEqualTo(7);
        assertThat(trend.get(0).path("day").asString()).isEqualTo("2026-10-26");
        JsonNode today = trend.get(6);
        assertThat(today.path("day").asString()).isEqualTo("2026-11-01");
        assertThat(today.path("active").asLong()).isEqualTo(1);
        assertThat(today.path("practiced").asLong()).isEqualTo(1);
        assertThat(overview.path("banks").isNull()).isTrue();

        // 5 分钟没有活动即不再算在线。
        clock.now.set(START.plus(Duration.ofMinutes(6)));
        assertThat(JSON.readTree(get("/api/admin/overview", admin.cookie()).body()).path("online").asLong()).isZero();
    }

    // ------------------------------------------------------------------ 辅助

    private record Session(String id, String cookie) {
    }

    private Session register() throws Exception {
        String email = "activity-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime() + "@example.test";
        HttpResponse<String> response = post("/api/auth/register",
                JSON.writeValueAsString(Map.of("identifier", email, "name", "U", "password", "pass12345")), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Session(JSON.readTree(response.body()).path("user").path("id").asString(),
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]);
    }

    private boolean practiced(String userId) {
        return jdbc.queryForObject("SELECT practiced FROM activity_daily WHERE user_id = ? AND day = ?", Boolean.class,
                userId, LocalDate.ofInstant(clock.now.get(), ZoneId.of("Asia/Shanghai")).toString());
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_daily", Long.class);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return send("GET", path, null, cookie);
    }

    private HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        return send("POST", path, body, cookie);
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Origin", ORIGIN);
        if (body != null) request.header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static class SteppingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(START);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        SteppingClock steppingClock() {
            return new SteppingClock();
        }
    }
}
