package cloud.treenewbee.quizforge.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 管理后台：/api/admin/** 统一权限、用户列表与详情、审计日志。 */
@TestPropertySource(properties = "qf.security.allowed-origin=" + AdminConsoleIntegrationTest.ORIGIN)
class AdminConsoleIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-admin.example.test";
    private static final String PASSWORD = "pass12345";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void close() {
        http.close();
    }

    // ------------------------------------------------------------------ 权限

    @Test
    void everyAdminEndpointRejectsAnonymousAndNonAdminUsers() throws Exception {
        Session admin = admin();
        Session member = register(email("member"));
        List<String[]> endpoints = List.of(
                new String[] {"GET", "/api/admin/users", null},
                new String[] {"GET", "/api/admin/users/" + admin.id(), null},
                new String[] {"GET", "/api/admin/audit", null},
                new String[] {"GET", "/api/admin/authentication", null},
                new String[] {"PUT", "/api/admin/authentication", "{\"invitationRequired\":true,\"revision\":0}"},
                new String[] {"GET", "/api/admin/invitations", null},
                new String[] {"POST", "/api/admin/invitations", "{\"count\":1}"},
                new String[] {"POST", "/api/admin/invitations/" + UUID.randomUUID() + "/revoke", "{}"},
                new String[] {"PUT", "/api/admin/users/" + admin.id() + "/block", "{\"blocked\":true,\"reason\":\"x\"}"},
                new String[] {"PUT", "/api/admin/users/" + admin.id() + "/quota", "{\"storageBytes\":1}"},
                new String[] {"GET", "/api/admin/not-a-page", null});
        for (String[] endpoint : endpoints) {
            assertThat(send(endpoint[0], endpoint[1], endpoint[2], null, ORIGIN).statusCode())
                    .as("anonymous " + endpoint[1]).isEqualTo(401);
            HttpResponse<String> denied = send(endpoint[0], endpoint[1], endpoint[2], member.cookie(), ORIGIN);
            assertThat(denied.statusCode()).as("member " + endpoint[1]).isEqualTo(403);
            assertThat(code(denied)).isEqualTo("FORBIDDEN");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_block WHERE user_id = ?", Long.class, admin.id()))
                .isZero();
        assertThat(send("GET", "/api/admin/users", null, admin.cookie(), ORIGIN).statusCode()).isEqualTo(200);

        // 管理员身份每次请求都从数据库读取：取消管理员后立即失去后台权限。
        jdbc.update("UPDATE account_user SET is_admin = FALSE WHERE id = ?", admin.id());
        assertThat(send("GET", "/api/admin/users", null, admin.cookie(), ORIGIN).statusCode()).isEqualTo(403);
    }

    @Test
    void adminWritesRequireTheSiteOrigin() throws Exception {
        Session admin = admin();
        Session target = register(email("origin"));
        String path = "/api/admin/users/" + target.id() + "/block";
        String body = "{\"blocked\":true,\"reason\":\"跨站\"}";
        for (String origin : new String[] {null, "https://evil.example"}) {
            HttpResponse<String> rejected = send("PUT", path, body, admin.cookie(), origin);
            assertThat(rejected.statusCode()).isEqualTo(403);
            assertThat(code(rejected)).isEqualTo("ORIGIN_REJECTED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_block WHERE user_id = ?", Long.class, target.id()))
                .isZero();
    }

    // ------------------------------------------------------------------ 用户列表

    @Test
    void userListUsesKeysetPaginationNewestFirstAndCapsPagesAtFifty() throws Exception {
        Session admin = admin();
        String token = "kp" + SEQUENCE.incrementAndGet() + "x" + System.nanoTime();
        Instant base = Instant.parse("2026-11-01T00:00:00Z");
        List<String> expected = new ArrayList<>();
        // 55 个账号；每 5 个共用同一注册时间，验证同一时间按 ID 继续翻页、不重不漏。
        for (int i = 0; i < 55; i++) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO account_user (id, email, name, password_hash, password_algo, is_admin, created_at,
                                              updated_at, version)
                    VALUES (?, ?, ?, 'x', 'scrypt-v1', FALSE, ?, ?, 0)
                    """, id, token + "-" + i + "@example.test", "名" + i,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(base.plusSeconds(i / 5), java.time.ZoneOffset.UTC)),
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(base.plusSeconds(i / 5), java.time.ZoneOffset.UTC)));
            expected.add(id);
        }

        JsonNode capped = json(send("GET", "/api/admin/users?q=" + token + "&limit=500", null, admin.cookie(), ORIGIN));
        assertThat(capped.path("items").size()).isEqualTo(50);
        assertThat(capped.path("nextCursor").isString()).isTrue();

        List<String> seen = new ArrayList<>();
        List<String> times = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json(send("GET", "/api/admin/users?q=" + token + "&limit=7"
                    + (cursor == null ? "" : "&cursor=" + cursor), null, admin.cookie(), ORIGIN));
            for (JsonNode item : page.path("items")) {
                seen.add(item.path("id").asString());
                times.add(item.path("createdAt").asString());
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(8);
        assertThat(seen).hasSize(55).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        assertThat(send("GET", "/api/admin/users?cursor=not-a-cursor", null, admin.cookie(), ORIGIN).statusCode())
                .isEqualTo(400);
    }

    @Test
    void userSearchMatchesEmailPhoneOrNameAndTreatsWildcardsLiterally() throws Exception {
        Session admin = admin();
        String token = "sr" + SEQUENCE.incrementAndGet() + "x" + System.nanoTime();
        String email = token + "@example.test";
        register(email);
        String phone = "1370" + String.format("%07d", SEQUENCE.incrementAndGet() + 1_000_000);
        Session phoneUser = register(phone);
        jdbc.update("UPDATE account_user SET name = ? WHERE id = ?", "昵称" + token, phoneUser.id());

        assertThat(ids(admin, token)).hasSize(2);
        assertThat(ids(admin, phone.substring(4))).containsExactly(phoneUser.id());
        assertThat(ids(admin, "昵称" + token)).containsExactly(phoneUser.id());
        assertThat(ids(admin, token.substring(0, 2) + "%")).isEmpty();
        assertThat(ids(admin, "_" + token.substring(1))).isEmpty();
    }

    // ------------------------------------------------------------------ 用户详情与操作

    @Test
    void userDetailShowsContactsBlockAndStorageAndOperationsAreAudited() throws Exception {
        Session admin = admin();
        String phone = "1360" + String.format("%07d", SEQUENCE.incrementAndGet() + 2_000_000);
        Session target = register(phone);
        jdbc.update("UPDATE account_user_phone SET verified = TRUE, verified_at = '2026-11-01 08:00:00.000' WHERE user_id = ?",
                target.id());

        HttpResponse<String> blocked = send("PUT", "/api/admin/users/" + target.id() + "/block",
                "{\"blocked\":true,\"reason\":\"违规上传\"}", admin.cookie(), ORIGIN);
        assertThat(blocked.statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/admin/users/" + target.id() + "/quota",
                "{\"storageBytes\":1048576,\"dailyDownloadBytes\":null}", admin.cookie(), ORIGIN).statusCode())
                .isEqualTo(200);

        JsonNode detail = json(send("GET", "/api/admin/users/" + target.id(), null, admin.cookie(), ORIGIN));
        JsonNode user = detail.path("user");
        assertThat(user.path("email").isNull()).isTrue();
        assertThat(user.path("phone").path("value").asString()).isEqualTo("+86" + phone);
        assertThat(user.path("phone").path("verified").asBoolean()).isTrue();
        assertThat(user.path("phone").path("verifiedAt").asString()).isEqualTo("2026-11-01T08:00:00Z");
        assertThat(user.path("block").path("reason").asString()).isEqualTo("违规上传");
        assertThat(user.path("block").path("actorId").asString()).isEqualTo(admin.id());
        assertThat(user.path("block").path("actorName").asString()).isEqualTo("Admin");
        assertThat(detail.path("storage").path("limitBytes").asLong()).isEqualTo(1_048_576);
        assertThat(detail.path("storage").path("overrideBytes").asLong()).isEqualTo(1_048_576);
        assertThat(detail.path("storage").path("usedBytes").asLong()).isZero();
        assertThat(detail.toString()).doesNotContain("password");

        // 两项操作都写入统一审计，记录操作的管理员。
        JsonNode audit = json(send("GET", "/api/admin/audit?limit=50", null, admin.cookie(), ORIGIN));
        List<String> actions = new ArrayList<>();
        for (JsonNode entry : audit.path("items")) {
            if (entry.path("target").asString().equals(target.id())) {
                actions.add(entry.path("module").asString() + "/" + entry.path("action").asString());
                assertThat(entry.path("actor").path("id").asString()).isEqualTo(admin.id());
            }
        }
        assertThat(actions).containsExactly("quota/quota-adjusted", "account/user-blocked");

        assertThat(send("GET", "/api/admin/users/" + UUID.randomUUID(), null, admin.cookie(), ORIGIN).statusCode())
                .isEqualTo(404);
    }

    @Test
    void auditLogIsReadOnlyKeysetPagedAndContainsNoInvitationCodes() throws Exception {
        Session admin = admin();
        HttpResponse<String> created = send("POST", "/api/admin/invitations", "{\"count\":2,\"days\":3}", admin.cookie(), ORIGIN);
        assertThat(created.statusCode()).isEqualTo(201);
        String code = json(created).path("items").get(0).path("code").asString();
        // 至少 11 条审计记录，保证下面的 5 页都有内容。
        for (int i = 0; i < 10; i++) {
            assertThat(send("POST", "/api/admin/invitations", "{\"count\":1}", admin.cookie(), ORIGIN).statusCode())
                    .isEqualTo(201);
        }
        // 每页 2 条翻 5 页，与一次取 10 条的顺序完全一致（不重不漏）。
        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 5; i++) {
            JsonNode page = json(send("GET", "/api/admin/audit?limit=2" + (cursor == null ? "" : "&cursor=" + cursor),
                    null, admin.cookie(), ORIGIN));
            page.path("items").forEach(entry -> paged.add(entry.path("id").asString()));
            cursor = page.path("nextCursor").asString();
            assertThat(cursor).isNotBlank();
        }
        List<String> direct = new ArrayList<>();
        json(send("GET", "/api/admin/audit?limit=10", null, admin.cookie(), ORIGIN)).path("items")
                .forEach(entry -> direct.add(entry.path("id").asString()));
        assertThat(paged).hasSize(10).doesNotHaveDuplicates().isEqualTo(direct);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE CAST(detail AS CHAR) LIKE ?",
                Integer.class, "%" + code + "%")).isZero();
        // 审计日志只能查看：没有修改或删除的接口。
        for (String method : new String[] {"PUT", "DELETE", "POST"}) {
            assertThat(send(method, "/api/admin/audit", "{}", admin.cookie(), ORIGIN).statusCode())
                    .isIn(404, 405);
        }
    }

    @Test
    void accountAuditWasMergedIntoAdminAudit() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name IN ('admin_audit', 'account_audit')
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'account_user' AND index_name = 'idx_user_created'
                """, Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 辅助

    private record Session(String id, String cookie) {
    }

    private Session admin() throws Exception {
        Session session = register(email("admin"));
        jdbc.update("UPDATE account_user SET is_admin = TRUE, name = 'Admin' WHERE id = ?", session.id());
        return session;
    }

    private Session register(String identifier) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("identifier", identifier, "name", "User", "password", PASSWORD));
        HttpResponse<String> response = send("POST", "/api/auth/register", JSON.writeValueAsString(body), null, ORIGIN);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        return new Session(json(response).path("user").path("id").asString(), cookie);
    }

    private List<String> ids(Session admin, String q) throws Exception {
        List<String> ids = new ArrayList<>();
        String encoded = java.net.URLEncoder.encode(q, StandardCharsets.UTF_8);
        json(send("GET", "/api/admin/users?q=" + encoded, null, admin.cookie(), ORIGIN)).path("items")
                .forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    private static String email(String label) {
        return label + "-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime() + "@example.test";
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return JSON.readTree(response.body());
    }

    private static String code(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("error").path("code").asString();
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie, String origin)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) request.header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
