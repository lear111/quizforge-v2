package cloud.treenewbee.quizforge.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.notification.application.NotificationService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 站内通知中心（功能盘点 I 节）：管理员发布 / 删除 / 置顶、铃铛红点、已读、题库通知筛选、权限与审计。 */
@TestPropertySource(properties = "qf.security.allowed-origin=" + NotificationIntegrationTest.ORIGIN)
class NotificationIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-notify.example.test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notifications;

    private final HttpClient http = HttpClient.newHttpClient();

    // 通知对所有人可见，测试之间互相影响：每个测试从空表开始。
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notification_read");
        jdbc.update("DELETE FROM notification_seen");
        jdbc.update("DELETE FROM notification");
    }

    @AfterEach
    void close() {
        http.close();
    }

    // ------------------------------------------------------------------ 权限

    @Test
    void adminEndpointsRequireAdministratorsAndUserEndpointsRequireLogin() throws Exception {
        Session member = register();
        for (String[] endpoint : List.of(
                new String[] {"GET", "/api/admin/notifications", null},
                new String[] {"POST", "/api/admin/notifications", "{\"title\":\"t\",\"body\":\"b\",\"audience\":\"all\"}"},
                new String[] {"PUT", "/api/admin/notifications/" + UUID.randomUUID() + "/pin", "{\"pinned\":true}"},
                new String[] {"DELETE", "/api/admin/notifications/" + UUID.randomUUID(), null})) {
            assertThat(send(endpoint[0], endpoint[1], endpoint[2], member.cookie()).statusCode()).as(endpoint[1]).isEqualTo(403);
            assertThat(send(endpoint[0], endpoint[1], endpoint[2], null).statusCode()).as(endpoint[1]).isEqualTo(401);
        }
        for (String path : List.of("/api/notifications", "/api/notifications/status")) {
            assertThat(send("GET", path, null, null).statusCode()).isEqualTo(401);
        }
        assertThat(count("notification")).isZero();
    }

    // ------------------------------------------------------------------ 管理员

    @Test
    void adminsPublishPinAndDeleteButCannotEditAndEverythingIsAudited() throws Exception {
        Session admin = admin();
        String first = publish(admin, "维护公告", "今晚 22:00 维护。\n详情：https://example.test/a", "all", null, false);
        String contact = publish(admin, "联系管理员", "邮箱 admin@example.test", "all", null, true);
        // 发给所有人的通知只存一份。
        register();
        register();
        assertThat(count("notification")).isEqualTo(2);

        JsonNode list = json(send("GET", "/api/admin/notifications", null, admin.cookie()));
        assertThat(list.path("items").get(0).path("id").asString()).isEqualTo(contact);
        assertThat(list.path("items").get(0).path("createdBy").path("id").asString()).isEqualTo(admin.id());
        assertThat(list.path("items").get(1).path("body").asString()).isEqualTo("今晚 22:00 维护。\n详情：https://example.test/a");

        assertThat(send("PUT", "/api/admin/notifications/" + first + "/pin", "{\"pinned\":true}", admin.cookie()).statusCode())
                .isEqualTo(204);
        assertThat(send("DELETE", "/api/admin/notifications/" + contact, null, admin.cookie()).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/admin/notifications/" + contact, null, admin.cookie()).statusCode()).isEqualTo(404);
        // 不能修改：没有修改内容的接口。
        assertThat(send("PUT", "/api/admin/notifications/" + first, "{\"title\":\"改\"}", admin.cookie()).statusCode())
                .isIn(404, 405);
        assertThat(send("PATCH", "/api/admin/notifications/" + first, "{\"title\":\"改\"}", admin.cookie()).statusCode())
                .isIn(404, 405);

        List<String> actions = jdbc.queryForList("SELECT action FROM admin_audit WHERE module = 'notification' "
                + "AND actor_id = ? ORDER BY created_at, id", String.class, admin.id());
        assertThat(actions).containsExactly("notification-created", "notification-created", "notification-pinned",
                "notification-deleted");
    }

    @Test
    void titleAndBodyAreBoundedPlainText() throws Exception {
        Session admin = admin();
        String target = register().id();
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("title", "x".repeat(101), "body", "b", "audience", "all"),
                Map.<String, Object>of("title", "两行\n标题", "body", "b", "audience", "all"),
                Map.<String, Object>of("title", "   ", "body", "b", "audience", "all"),
                Map.<String, Object>of("title", "t", "body", "😀".repeat(5001), "audience", "all"),
                Map.<String, Object>of("title", "t", "body", " \n ", "audience", "all"),
                Map.<String, Object>of("title", "t", "body", "b", "audience", "bank"),
                Map.<String, Object>of("title", "t", "body", "b", "audience", "user", "userId", UUID.randomUUID().toString()))) {
            assertThat(send("POST", "/api/admin/notifications", JSON.writeValueAsString(body), admin.cookie()).statusCode())
                    .as(body.toString()).isEqualTo(400);
        }
        // 上限本身可以发布；HTML 原样保存为文本（前端以 textContent 显示）。
        String id = publish(admin, "y".repeat(100), "<b>粗体</b>" + "😀".repeat(4990), "user", target, false);
        assertThat(jdbc.queryForObject("SELECT body FROM notification WHERE id = ?", String.class, id)).startsWith("<b>粗体</b>");
        assertThat(count("notification")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 用户：铃铛、列表、已读

    @Test
    void bellShowsNewNotificationsUntilTheUserOpensTheList() throws Exception {
        Session admin = admin();
        Session user = register();
        assertThat(unseen(user)).isFalse();

        publish(admin, "公告一", "正文", "all", null, false);
        assertThat(unseen(user)).isTrue();
        // 打开通知界面后红点消失，即使没有逐条查看。
        assertThat(send("POST", "/api/notifications/seen", null, user.cookie()).statusCode()).isEqualTo(204);
        assertThat(unseen(user)).isFalse();
        JsonNode list = json(send("GET", "/api/notifications", null, user.cookie()));
        assertThat(list.path("unseen").asBoolean()).isFalse();
        assertThat(list.path("items").get(0).path("read").asBoolean()).isFalse();

        // 再发一条，红点重新出现；发给其他用户的通知不影响该用户。
        Session other = register();
        publish(admin, "私信", "只给 other", "user", other.id(), false);
        assertThat(unseen(user)).isFalse();
        assertThat(unseen(other)).isTrue();
        publish(admin, "公告二", "正文", "all", null, false);
        assertThat(unseen(user)).isTrue();
    }

    @Test
    void listPutsPinnedFirstAndReadMarksOnlyTheOpenedItem() throws Exception {
        Session admin = admin();
        Session user = register();
        Session other = register();
        String older = publish(admin, "较早", "a", "all", null, false);
        String pinned = publish(admin, "置顶", "b", "all", null, true);
        String newer = publish(admin, "较新", "c", "all", null, false);
        String mine = publish(admin, "给我的", "d", "user", user.id(), false);
        String theirs = publish(admin, "给别人的", "e", "user", other.id(), false);

        JsonNode list = json(send("GET", "/api/notifications", null, user.cookie()));
        List<String> ids = new ArrayList<>();
        list.path("items").forEach(item -> ids.add(item.path("id").asString()));
        assertThat(ids).containsExactly(pinned, mine, newer, older);

        assertThat(json(send("GET", "/api/notifications/" + newer, null, user.cookie())).path("body").asString()).isEqualTo("c");
        assertThat(send("POST", "/api/notifications/" + newer + "/read", null, user.cookie()).statusCode()).isEqualTo(204);
        assertThat(send("POST", "/api/notifications/" + newer + "/read", null, user.cookie()).statusCode()).isEqualTo(204);
        JsonNode after = json(send("GET", "/api/notifications", null, user.cookie()));
        Map<String, Boolean> read = new LinkedHashMap<>();
        after.path("items").forEach(item -> read.put(item.path("id").asString(), item.path("read").asBoolean()));
        assertThat(read).containsEntry(newer, true).containsEntry(older, false).containsEntry(pinned, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_read WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(1);

        // IDOR：别人的通知既读不到，也不能标记已读。
        assertThat(send("GET", "/api/notifications/" + theirs, null, user.cookie()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/notifications/" + theirs + "/read", null, user.cookie()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_read WHERE notification_id = ?", Long.class,
                theirs)).isZero();
    }

    // ------------------------------------------------------------------ 题库通知（供题库广场调用）

    @Test
    void bankNotificationsReachOnlyCurrentOwners() throws Exception {
        Session owner = register();
        Session former = register();
        Session stranger = register();
        String bankKey = "bank-" + UUID.randomUUID();
        collection(owner.id(), bankKey, false);
        collection(former.id(), bankKey, true);

        String id = notifications.publishToBankOwners(bankKey, "题库升级到 1.1", "新增 10 道题", owner.id());
        assertThat(count("notification")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM notification WHERE id = ?", String.class, id)).isEqualTo("publisher");

        assertThat(unseen(owner)).isTrue();
        assertThat(unseen(former)).isFalse();
        assertThat(unseen(stranger)).isFalse();
        assertThat(json(send("GET", "/api/notifications", null, owner.cookie())).path("items").get(0).path("id").asString())
                .isEqualTo(id);
        assertThat(json(send("GET", "/api/notifications", null, stranger.cookie())).path("items").size()).isZero();
        assertThat(send("GET", "/api/notifications/" + id, null, stranger.cookie()).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 性能：红点查询的执行计划

    @Test
    void bellQueryUsesTheAudienceIndexInsteadOfScanning() throws Exception {
        Session user = register();
        String bankKey = "bank-" + UUID.randomUUID();
        collection(user.id(), bankKey, false);
        // 足够多的通知，让优化器在索引与全表扫描之间真正做出选择。
        jdbc.update("""
                INSERT INTO notification (id, audience, target_id, source, title, body, pinned, created_by, created_at)
                SELECT UUID(), ELT(1 + seq % 3, 'all', 'user', 'bank'),
                       CASE seq % 3 WHEN 0 THEN '' WHEN 1 THEN UUID() ELSE CONCAT('k', seq) END,
                       'admin', 't', 'b', FALSE, NULL,
                       TIMESTAMPADD(SECOND, -seq, TIMESTAMP '2026-11-01 00:00:00')
                  FROM (WITH RECURSIVE s (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 1000)
                        SELECT n + k AS seq FROM s CROSS JOIN (SELECT 0 AS k UNION ALL SELECT 1000 UNION ALL SELECT 2000) b) numbers
                """);
        jdbc.execute("ANALYZE TABLE notification");
        List<Map<String, Object>> plan = jdbc.queryForList("""
                EXPLAIN SELECT EXISTS (SELECT 1 FROM notification
                                        WHERE audience = 'all' AND target_id = '' AND created_at > ?)
                            OR EXISTS (SELECT 1 FROM notification
                                        WHERE audience = 'user' AND target_id = ? AND created_at > ?)
                            OR EXISTS (SELECT 1 FROM notification
                                        WHERE audience = 'bank' AND target_id IN (?) AND created_at > ?)
                """, "2026-10-31 23:00:00", user.id(), "2026-10-31 23:00:00", bankKey, "2026-10-31 23:00:00");
        System.out.println("红点查询执行计划：");
        plan.forEach(row -> System.out.println("  " + row));
        List<Map<String, Object>> tables = plan.stream().filter(row -> "notification".equals(row.get("table"))).toList();
        assertThat(tables).hasSize(3).allSatisfy(row -> {
            assertThat(row.get("key")).isEqualTo("idx_notification_audience");
            assertThat(row.get("type")).isIn("range", "ref");
            assertThat(String.valueOf(row.get("Extra"))).contains("Using index");
        });
    }

    // ------------------------------------------------------------------ 辅助

    private record Session(String id, String cookie) {
    }

    private Session admin() throws Exception {
        Session session = register();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", session.id());
        return session;
    }

    private Session register() throws Exception {
        String email = "notify-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime() + "@example.test";
        HttpResponse<String> response = send("POST", "/api/auth/register", JSON.writeValueAsString(
                Map.of("identifier", email, "name", "U", "password", "pass12345")), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Session(JSON.readTree(response.body()).path("user").path("id").asString(),
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]);
    }

    private String publish(Session admin, String title, String body, String audience, String userId, boolean pinned)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("title", title, "body", body, "audience", audience,
                "pinned", pinned));
        if (userId != null) request.put("userId", userId);
        HttpResponse<String> response = send("POST", "/api/admin/notifications", JSON.writeValueAsString(request),
                admin.cookie());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).path("id").asString();
    }

    private boolean unseen(Session user) throws Exception {
        return json(send("GET", "/api/notifications/status", null, user.cookie())).path("unseen").asBoolean();
    }

    private void collection(String userId, String bankKey, boolean deleted) {
        jdbc.update("""
                INSERT INTO practice_collection (id, user_id, bank_id, title, source, source_bank_key, created_at, deleted_at)
                VALUES (?, ?, ?, '题库', '{}', ?, NOW(3), ?)
                """, UUID.randomUUID().toString(), userId, UUID.randomUUID().toString(), bankKey,
                deleted ? java.sql.Timestamp.valueOf("2026-11-01 00:00:00") : null);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
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
}
