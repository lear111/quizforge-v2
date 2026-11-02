package cloud.treenewbee.quizforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.ai.application.AiBalanceService;
import cloud.treenewbee.quizforge.ai.application.AiCodeCrypto;
import cloud.treenewbee.quizforge.ai.application.AiReconciler;
import cloud.treenewbee.quizforge.identity.maintenance.MaintenanceCommand;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** L5.1：AI 余额、流水、兑换码、管理员调整与对账（设计 08 第 1 节，FR-08、FR-09）。 */
@TestPropertySource(properties = "qf.security.allowed-origin=" + AiBalanceIntegrationTest.ORIGIN)
class AiBalanceIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-ai.example.test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiBalanceService balances;
    @Autowired AiReconciler reconciler;
    @Autowired AiCodeCrypto crypto;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void close() {
        http.close();
    }

    // ------------------------------------------------------------------ 默认额度

    @Test
    void registrationOpensTheAccountWithExactlyOneDefaultCredit() throws Exception {
        Session user = register();
        assertThat(balance(user)).isEqualTo(10_000);
        assertThat(jdbc.queryForList("SELECT CONCAT(kind, '/', ref, '/', seq, '/', delta, '/', balance_after) FROM ai_ledger "
                + "WHERE user_id = ?", String.class, user.id()))
                .containsExactly("default_credit/" + user.id() + "/1/10000/10000");
        // 再次发放同一用户的默认额度被唯一约束拒绝，余额不变。
        assertThatThrownBy(() -> balances.change(user.id(), 10_000, AiBalanceService.Kind.DEFAULT_CREDIT, user.id(), null, null))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("LEDGER_DUPLICATE"));
        assertThat(balance(user)).isEqualTo(10_000);
        assertThat(ledgerCount(user.id())).isEqualTo(1);
    }

    @Test
    void v16GrantsTheDefaultCreditToExistingUsersOnce() throws Exception {
        String database = "ai_backfill_" + System.nanoTime();
        // 应用账号没有建库权限：用 root 建一个只属于本测试的库，从 V15 升级到 V16。
        JdbcTemplate root = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        root.execute("CREATE DATABASE " + database);
        try {
            String url = MYSQL.getJdbcUrl().replace("/quizforge", "/" + database);
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(url, "root", MYSQL.getPassword())
                    .locations("classpath:db/migration");
            flyway.target("15").load().migrate();
            JdbcTemplate old = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, "root", MYSQL.getPassword()));
            for (String id : List.of("00000000-0000-7000-8000-000000000001", "00000000-0000-7000-8000-000000000002")) {
                old.update("INSERT INTO account_user (id, email, name, password_hash, password_algo, created_at, updated_at) "
                        + "VALUES (?, ?, '老用户', 'x', 'scrypt-v1', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", id, id + "@example.test");
            }
            flyway.target("16").load().migrate();
            assertThat(old.queryForList("SELECT CONCAT(a.user_id = l.ref, '/', a.balance, '/', a.ledger_seq, '/', l.seq, '/', "
                    + "l.delta, '/', l.balance_after, '/', l.kind) FROM ai_account a JOIN ai_ledger l ON l.user_id = a.user_id",
                    String.class)).containsExactly("1/10000/1/1/10000/10000/default_credit",
                    "1/10000/1/1/10000/10000/default_credit");
        } finally {
            root.execute("DROP DATABASE " + database);
        }
    }

    // ------------------------------------------------------------------ 唯一入口、并发与幂等

    @Test
    void concurrentChangesKeepBalanceAndLedgerConsistent() throws Exception {
        Session user = register();
        int writers = 24;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            long delta = i % 2 == 0 ? 3_000 : -1_250;
            String ref = "concurrent-" + user.id() + "-" + i;
            tasks.add(() -> balances.change(user.id(), delta, delta > 0 ? AiBalanceService.Kind.REFUND
                    : AiBalanceService.Kind.AI_CALL, ref, null, null));
        }
        for (Future<Object> result : runTogether(tasks)) result.get();
        long expected = 10_000 + (writers / 2) * (3_000L - 1_250);
        assertThat(balance(user)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT ledger_seq FROM ai_account WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(writers + 1L);
        assertThat(jdbc.queryForList("SELECT seq FROM ai_ledger WHERE user_id = ? ORDER BY seq", Long.class, user.id()))
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, writers + 1).boxed().toList());
        assertConsistent(user.id());
    }

    @Test
    void duplicateSourceIsRejectedAndBalanceIsUnchangedEvenWhenConcurrent() throws Exception {
        Session user = register();
        String ref = "task-" + UUID.randomUUID();
        balances.charge(user.id(), 300, ref);
        assertThatThrownBy(() -> balances.charge(user.id(), 300, ref))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(balance(user)).isEqualTo(9_700);

        String racing = "task-" + UUID.randomUUID();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) tasks.add(() -> balances.charge(user.id(), 500, racing));
        int succeeded = 0;
        for (Future<Object> result : runTogether(tasks)) {
            try {
                result.get();
                succeeded++;
            } catch (java.util.concurrent.ExecutionException error) {
                assertThat(error.getCause()).isInstanceOf(ApiException.class);
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(balance(user)).isEqualTo(9_200);
        assertThat(ledgerCount(user.id())).isEqualTo(3);
        assertConsistent(user.id());
    }

    @Test
    void balanceMayGoNegativeAndIsShownAsOverdrawn() throws Exception {
        Session user = register();
        assertThat(balances.hasPositiveBalance(user.id())).isTrue();
        balances.charge(user.id(), 10_000, "task-" + UUID.randomUUID());
        assertThat(balances.hasPositiveBalance(user.id())).isFalse();
        balances.charge(user.id(), 2_500, "task-" + UUID.randomUUID());
        JsonNode account = json(send("GET", "/api/account/ai", null, user.cookie()));
        assertThat(account.path("balance").asLong()).isEqualTo(-2_500);
        assertThat(account.path("overdrawn").asBoolean()).isTrue();
        assertThatThrownBy(() -> balances.charge(user.id(), 0, "task-" + UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertConsistent(user.id());
    }

    @Test
    void ledgerIsKeysetPagedAndOnlyShowsTheSessionUser() throws Exception {
        Session user = register();
        Session other = register();
        for (int i = 0; i < 4; i++) balances.charge(user.id(), 10 + i, "page-" + user.id() + "-" + i);
        JsonNode first = json(send("GET", "/api/account/ai/ledger?limit=3", null, user.cookie()));
        assertThat(first.path("items")).hasSize(3);
        assertThat(first.path("items").get(0).path("seq").asLong()).isEqualTo(5);
        assertThat(first.path("items").get(0).has("actor")).isFalse();
        long before = first.path("nextBefore").asLong();
        JsonNode second = json(send("GET", "/api/account/ai/ledger?limit=3&before=" + before, null, user.cookie()));
        assertThat(second.path("items")).hasSize(2);
        assertThat(second.path("items").get(1).path("kind").asString()).isEqualTo("default_credit");
        assertThat(second.path("nextBefore").isNull()).isTrue();

        // IDOR：请求中附带的其他用户标识不起作用，只返回会话用户自己的数据。
        JsonNode foreign = json(send("GET", "/api/account/ai/ledger?userId=" + user.id(), null, other.cookie()));
        assertThat(foreign.path("items")).hasSize(1);
        assertThat(json(send("GET", "/api/account/ai?userId=" + user.id(), null, other.cookie())).path("balance").asLong())
                .isEqualTo(10_000);
        for (String path : List.of("/api/account/ai", "/api/account/ai/ledger")) {
            assertThat(send("GET", path, null, null).statusCode()).isEqualTo(401);
        }
        assertThat(send("POST", "/api/account/ai/redeem", "{\"code\":\"AI-0000-0000-0000-0000\"}", null).statusCode())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ 兑换码

    @Test
    void codesAreStoredAsDigestAndCipherAndRedeemOnce() throws Exception {
        Session admin = admin();
        Session user = register();
        JsonNode issued = createCodes(admin, 2, 50_000, 30, "活动");
        String code = issued.get(0).path("code").asString();
        String id = issued.get(0).path("id").asString();
        assertThat(code).matches("AI-[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}");

        Map<String, Object> row = jdbc.queryForMap("SELECT prefix, HEX(digest) AS digest, code_cipher FROM ai_redeem_code "
                + "WHERE id = ?", id);
        assertThat(row.get("prefix")).isEqualTo(code.substring(0, 7));
        String body = code.substring(3).replace("-", "");
        assertThat((String) row.get("digest")).isEqualToIgnoringCase(HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))));
        assertThat((String) row.get("code_cipher")).doesNotContain(body);
        assertThat(crypto.decrypt((String) row.get("code_cipher"))).isEqualTo(code);
        // 列表只给前缀，不给完整码。
        JsonNode list = json(send("GET", "/api/admin/ai-codes", null, admin.cookie()));
        assertThat(list.toString()).doesNotContain(body).contains(code.substring(0, 7));

        // 输入时忽略大小写、空白与连字符，O→0、I/L→1。
        String typed = " " + code.toLowerCase().replace("-", " ").replace('0', 'o').replace('1', 'l') + " ";
        JsonNode redeemed = json(send("POST", "/api/account/ai/redeem", JSON.writeValueAsString(Map.of("code", typed)),
                user.cookie()));
        assertThat(redeemed.path("added").asLong()).isEqualTo(50_000);
        assertThat(redeemed.path("balance").asLong()).isEqualTo(60_000);
        assertThat(jdbc.queryForObject("SELECT ref FROM ai_ledger WHERE user_id = ? AND kind = 'redeem'", String.class,
                user.id())).isEqualTo(id);
        assertThat(redeem(user, code).statusCode()).isEqualTo(400);
        assertThat(redeem(register(), code).statusCode()).isEqualTo(400);
        assertThat(balance(user)).isEqualTo(60_000);

        // 已兑换的码不能停用；未兑换的可以停用，停用后不能兑换。
        assertThat(send("POST", "/api/admin/ai-codes/" + id + "/revoke", null, admin.cookie()).statusCode()).isEqualTo(409);
        String second = issued.get(1).path("code").asString();
        String secondId = issued.get(1).path("id").asString();
        assertThat(json(send("POST", "/api/admin/ai-codes/" + secondId + "/revoke", null, admin.cookie()))
                .path("state").asString()).isEqualTo("revoked");
        assertThat(redeem(user, second).statusCode()).isEqualTo(400);
        assertThat(balance(user)).isEqualTo(60_000);

        assertThat(jdbc.queryForList("SELECT action FROM admin_audit WHERE module = 'ai' AND actor_id = ? ORDER BY created_at, id",
                String.class, admin.id())).containsExactly("ai-codes-created", "ai-code-revoked");
        assertConsistent(user.id());
    }

    @Test
    void concurrentRedemptionsOfOneCodeSucceedOnlyOnce() throws Exception {
        Session admin = admin();
        String code = createCodes(admin, 1, 20_000, 30, null).get(0).path("code").asString();
        List<Session> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) users.add(register());
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (Session user : users) tasks.add(() -> redeem(user, code).statusCode());
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : runTogether(tasks)) statuses.add(result.get());
        assertThat(statuses).containsOnly(200, 400);
        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
        long total = 0;
        for (Session user : users) total += balance(user);
        assertThat(total).isEqualTo(8 * 10_000L + 20_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_ledger WHERE kind = 'redeem' AND ref = "
                + "(SELECT id FROM ai_redeem_code WHERE prefix = ? ORDER BY seq DESC LIMIT 1)", Long.class,
                code.substring(0, 7))).isEqualTo(1);
    }

    @Test
    void expiredCodesAreRejectedAndTenFailuresLockTheUser() throws Exception {
        Session admin = admin();
        Session user = register();
        JsonNode issued = createCodes(admin, 2, 10_000, 1, null);
        jdbc.update("UPDATE ai_redeem_code SET expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id = ?",
                issued.get(0).path("id").asString());
        HttpResponse<String> expired = redeem(user, issued.get(0).path("code").asString());
        assertThat(expired.statusCode()).isEqualTo(400);
        assertThat(expired.body()).contains("REDEEM_CODE_INVALID");

        for (int i = 0; i < 9; i++) assertThat(redeem(user, "AI-ZZZZ-ZZZZ-ZZZZ-ZZZ" + (i % 10)).statusCode()).isEqualTo(400);
        // 第 10 次输错之后锁定：有效的码也不能兑换。
        HttpResponse<String> locked = redeem(user, issued.get(1).path("code").asString());
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.body()).contains("REDEEM_LOCKED");
        assertThat(balance(user)).isEqualTo(10_000);
        // 锁定只针对该用户。
        assertThat(redeem(register(), issued.get(1).path("code").asString()).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 管理员

    @Test
    void adminAdjustmentsRequireAReasonAndAreAudited() throws Exception {
        Session admin = admin();
        Session user = register();
        String path = "/api/admin/users/" + user.id() + "/ai-balance";
        for (String body : List.of("{\"delta\":5000,\"reason\":\"  \"}", "{\"delta\":5000}", "{\"delta\":0,\"reason\":\"r\"}",
                "{\"delta\":10000000001,\"reason\":\"r\"}", "{\"delta\":1,\"reason\":\"" + "长".repeat(201) + "\"}")) {
            assertThat(send("POST", path, body, admin.cookie()).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(send("POST", "/api/admin/users/" + UUID.randomUUID() + "/ai-balance",
                "{\"delta\":5000,\"reason\":\"补偿\"}", admin.cookie()).statusCode()).isEqualTo(404);

        assertThat(send("POST", path, "{\"delta\":5000,\"reason\":\"活动补偿\"}", admin.cookie()).statusCode()).isEqualTo(200);
        assertThat(send("POST", path, "{\"delta\":-20000,\"reason\":\"误发回收\"}", admin.cookie()).statusCode()).isEqualTo(200);
        assertThat(balance(user)).isEqualTo(-5_000);

        JsonNode detail = json(send("GET", "/api/admin/users/" + user.id() + "/ai", null, admin.cookie()));
        assertThat(detail.path("balance").path("overdrawn").asBoolean()).isTrue();
        JsonNode latest = detail.path("ledger").path("items").get(0);
        assertThat(latest.path("kind").asString()).isEqualTo("admin");
        assertThat(latest.path("reason").asString()).isEqualTo("误发回收");
        assertThat(latest.path("actor").path("id").asString()).isEqualTo(admin.id());

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, target, detail FROM admin_audit "
                + "WHERE module = 'ai' AND actor_id = ? ORDER BY created_at, id", admin.id());
        assertThat(audit).extracting(entry -> entry.get("action")).containsExactly("ai-balance-adjusted", "ai-balance-adjusted");
        assertThat(audit).extracting(entry -> entry.get("target")).containsOnly(user.id());
        assertThat(String.valueOf(audit.get(1).get("detail"))).contains("误发回收").contains("-20000");
        assertConsistent(user.id());
    }

    @Test
    void adminEndpointsRequireAdministrators() throws Exception {
        Session member = register();
        String code = createCodes(admin(), 1, 10_000, 30, null).get(0).path("id").asString();
        for (String[] endpoint : List.of(
                new String[] {"GET", "/api/admin/ai-codes", null},
                new String[] {"POST", "/api/admin/ai-codes", "{\"count\":1,\"points\":10000,\"days\":30}"},
                new String[] {"POST", "/api/admin/ai-codes/" + code + "/revoke", null},
                new String[] {"GET", "/api/admin/users/" + member.id() + "/ai", null},
                new String[] {"POST", "/api/admin/users/" + member.id() + "/ai-balance", "{\"delta\":100000,\"reason\":\"自己加\"}"})) {
            assertThat(send(endpoint[0], endpoint[1], endpoint[2], member.cookie()).statusCode()).as(endpoint[1]).isEqualTo(403);
            assertThat(send(endpoint[0], endpoint[1], endpoint[2], null).statusCode()).as(endpoint[1]).isEqualTo(401);
        }
        assertThat(balance(member)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM ai_redeem_code WHERE id = ?", Boolean.class, code)).isTrue();
    }

    @Test
    void codeCreationIsBounded() throws Exception {
        Session admin = admin();
        for (String body : List.of("{\"count\":0,\"points\":10000,\"days\":30}", "{\"count\":201,\"points\":10000,\"days\":30}",
                "{\"count\":1,\"points\":99,\"days\":30}", "{\"count\":1,\"points\":100000001,\"days\":30}",
                "{\"count\":1,\"points\":10000,\"days\":0}", "{\"count\":1,\"points\":10000,\"days\":30,\"note\":\""
                        + "注".repeat(61) + "\"}")) {
            assertThat(send("POST", "/api/admin/ai-codes", body, admin.cookie()).statusCode()).as(body).isEqualTo(400);
        }
    }

    // ------------------------------------------------------------------ 对账

    @Test
    void reconcilerReportsBalanceMismatchesAndLedgerGapsAndTheMaintenanceCommandRunsIt() throws Exception {
        Session user = register();
        balances.charge(user.id(), 100, "task-" + UUID.randomUUID());
        assertConsistent(user.id());
        try {
            jdbc.update("UPDATE ai_account SET balance = balance + 1 WHERE user_id = ?", user.id());
            jdbc.update("UPDATE ai_ledger SET balance_after = balance_after + 7 WHERE user_id = ? AND seq = 2", user.id());
            AiReconciler.Result result = reconciler.run();
            assertThat(result.balanceMismatches()).anySatisfy(mismatch -> {
                assertThat(mismatch.userId()).isEqualTo(user.id());
                assertThat(mismatch.balance()).isEqualTo(9_901);
                assertThat(mismatch.ledgerSum()).isEqualTo(9_900);
            });
            assertThat(result.ledgerGaps()).anySatisfy(gap -> {
                assertThat(gap.userId()).isEqualTo(user.id());
                assertThat(gap.seq()).isEqualTo(2);
            });
            Maintenance command = maintenance("ai-reconcile");
            assertThat(command.exit()).as(command.err()).isEqualTo(3);
            assertThat(command.out()).contains(user.id()).contains("\"balanceMismatches\"");
        } finally {
            jdbc.update("UPDATE ai_account SET balance = balance - 1 WHERE user_id = ?", user.id());
            jdbc.update("UPDATE ai_ledger SET balance_after = balance_after - 7 WHERE user_id = ? AND seq = 2", user.id());
        }
        assertConsistent(user.id());
        assertThat(maintenance("ai-reconcile", "extra").exit()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 辅助

    private record Session(String id, String cookie) {
    }

    private record Maintenance(int exit, String out, String err) {
    }

    private void assertConsistent(String userId) {
        AiReconciler.Result result = reconciler.run();
        assertThat(result.balanceMismatches()).noneSatisfy(mismatch -> assertThat(mismatch.userId()).isEqualTo(userId));
        assertThat(result.ledgerGaps()).noneSatisfy(gap -> assertThat(gap.userId()).isEqualTo(userId));
    }

    private <T> List<Future<T>> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> results = new ArrayList<>();
            for (Callable<T> task : tasks) {
                results.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<T> result : results) {
                try {
                    result.get();
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // 由调用方检查。
                }
            }
            return results;
        }
    }

    private Maintenance maintenance(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = MaintenanceCommand.run(args, Map.of(
                "spring.datasource.url", MYSQL.getJdbcUrl(),
                "spring.datasource.username", MYSQL.getUsername(),
                "spring.datasource.password", MYSQL.getPassword(),
                "qf.verification.mode", "fake",
                "qf.security.allowed-origin", ORIGIN),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Maintenance(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private JsonNode createCodes(Session admin, int count, long points, int days, String note) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("count", count, "points", points, "days", days));
        if (note != null) body.put("note", note);
        HttpResponse<String> response = send("POST", "/api/admin/ai-codes", JSON.writeValueAsString(body), admin.cookie());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return JSON.readTree(response.body()).path("items");
    }

    private HttpResponse<String> redeem(Session user, String code) throws Exception {
        return send("POST", "/api/account/ai/redeem", JSON.writeValueAsString(Map.of("code", code)), user.cookie());
    }

    private long balance(Session user) {
        return jdbc.queryForObject("SELECT balance FROM ai_account WHERE user_id = ?", Long.class, user.id());
    }

    private long ledgerCount(String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_ledger WHERE user_id = ?", Long.class, userId);
    }

    private Session admin() throws Exception {
        Session session = register();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", session.id());
        return session;
    }

    private Session register() throws Exception {
        String email = "ai-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime() + "@example.test";
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
