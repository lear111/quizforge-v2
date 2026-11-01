package cloud.treenewbee.quizforge.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.mysql.MySQLContainer;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 端到端：合成旧库（SQLite）→ 工具 → 真实 MySQL 8.4。目标库的表结构由 backend 的 Flyway 脚本创建，
 * 每个测试使用一个新建的空库。
 */
class MigrationIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("quizforge").withUsername("quizforge").withPassword("quizforge");
    private static final Path BACKEND_MIGRATIONS = Path.of("../../backend/src/main/resources/db/migration");
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREATED = "2026-01-02T03:04:05.678901Z";

    static {
        MYSQL.start();
    }

    @TempDir Path dir;
    private String targetUrl;

    @BeforeEach
    void newTargetDatabase() throws Exception {
        String name = "target_" + DATABASES.incrementAndGet();
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        targetUrl = MYSQL.getJdbcUrl().replace("/quizforge", "/" + name);
        Flyway.configure().dataSource(targetUrl, "root", MYSQL.getPassword())
                .locations("filesystem:" + BACKEND_MIGRATIONS.toAbsolutePath()).load().migrate();
    }

    // ------------------------------------------------------------------ 正常迁移

    @Test
    void migratesEveryAccountFieldAndOriginalPasswordsStillWork() throws Exception {
        Path source = dir.resolve("hub.sqlite");
        String admin, phoneOnly, both, blocked, upper;
        try (LegacyFixture legacy = LegacyFixture.create(source)) {
            admin = legacy.user("admin@example.test", "管理员", "admin-pass-1", true, CREATED);
            phoneOnly = legacy.user(null, "手机用户", "phone-pass-1", false, "2026-02-03T04:05:06Z");
            legacy.phone(phoneOnly, "+8613800138000", 1767225600123L);
            both = legacy.user("both@example.test", "两者都有", "both-pass-1", false, CREATED);
            legacy.phone(both, "+8613900139000", 0);
            legacy.unverified(both, "phone", "+8613900139000");
            legacy.unverified(both, "email", "old@example.test");
            blocked = legacy.user("blocked@example.test", "被封禁", "blocked-pass-1", false, CREATED);
            legacy.block(blocked, admin, "测试封禁", 1767225600999L);
            upper = legacy.user(" Upper@Example.TEST", "大写邮箱", "upper-pass-1", false, CREATED);
        }
        String before = LegacySource.sha256(source);

        Run inspect = run("inspect", "--source", source.toString(), "--report", dir.resolve("inspect.json").toString());
        assertThat(inspect.exit()).as(inspect.err()).isZero();
        assertThat(codes(inspect.json())).contains("EMAIL_NOT_NORMALIZED", "STALE_UNVERIFIED_CONTACT")
                .doesNotContain("EMAIL_COLLISION_CI");
        assertThat(inspect.json().path("summary").path("phoneOnly").asInt()).isEqualTo(1);
        assertThat(inspect.json().path("aiDefaultCredit").path("status").asString()).isEqualTo("pending-L5.1");

        Run migrate = run("migrate", "--source", source.toString());
        assertThat(migrate.exit()).as(migrate.err()).isZero();
        assertThat(migrate.json().path("migrated").asInt()).isEqualTo(5);
        assertThat(migrate.json().path("committed").asBoolean()).isTrue();

        try (Connection target = target(); Statement statement = target.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT email, is_admin, password_algo, created_at, "
                    + "updated_at, version FROM account_user WHERE id = '" + admin + "'")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("admin@example.test");
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getString(3)).isEqualTo("scrypt-v1");
                assertThat(rows.getString(4)).isEqualTo("2026-01-02 03:04:05.678");
                assertThat(rows.getString(5)).isEqualTo(rows.getString(4));
                assertThat(rows.getLong(6)).isZero();
            }
            try (ResultSet rows = statement.executeQuery("SELECT email, phone, verified, verified_at FROM account_user u "
                    + "JOIN account_user_phone p ON p.user_id = u.id WHERE u.id = '" + phoneOnly + "'")) {
                rows.next();
                assertThat(rows.getString(1)).isNull();
                assertThat(rows.getString(2)).isEqualTo("+8613800138000");
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.getString(4)).isEqualTo("2026-01-01 00:00:00.123");
            }
            try (ResultSet rows = statement.executeQuery("SELECT verified, verified_at FROM account_user_phone "
                    + "WHERE user_id = '" + both + "'")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isFalse();
                assertThat(rows.getString(2)).isNull();
            }
            // 只迁移与当前联系方式一致的未验证标记。
            assertThat(strings(statement, "SELECT channel FROM account_unverified_contact WHERE user_id = '" + both + "'"))
                    .containsExactly("phone");
            assertThat(strings(statement, "SELECT CONCAT(actor_id, '|', reason, '|', created_at) FROM account_block"))
                    .containsExactly(admin + "|测试封禁|2026-01-01 00:00:00.999");
            assertThat(strings(statement, "SELECT email FROM account_user WHERE id = '" + upper + "'"))
                    .containsExactly("upper@example.test");
        }

        Path known = dir.resolve("known.json");
        Files.writeString(known, """
                [{"identifier":"admin@example.test","password":"admin-pass-1","expect":"login"},
                 {"identifier":"138 0013 8000","password":"phone-pass-1","expect":"login"},
                 {"identifier":"+86 139-0013-9000","password":"both-pass-1","expect":"login"},
                 {"identifier":"UPPER@example.test","password":"upper-pass-1","expect":"login"},
                 {"identifier":"blocked@example.test","password":"blocked-pass-1","expect":"blocked"}]
                """);
        Path reportFile = dir.resolve("verify.json");
        Run verify = run("verify", "--source", source.toString(), "--known-passwords", known.toString(),
                "--report", reportFile.toString());
        assertThat(verify.exit()).as(verify.out()).isZero();
        JsonNode report = verify.json();
        assertThat(report.path("logins").path("summary").path("passed").asInt()).isEqualTo(5);
        assertThat(report.path("logins").path("summary").path("phoneOnlyAccounts").asInt()).isEqualTo(1);
        assertThat(report.path("summary").path("users").path("target").asInt()).isEqualTo(5);
        assertThat(report.path("summary").path("errors").asInt()).isZero();
        assertThat(report.path("summary").has("warnings")).isTrue();

        // 报告中没有完整联系方式、昵称、密码哈希；旧库副本未被修改。
        String text = Files.readString(reportFile);
        for (String secret : List.of("admin@example.test", "+8613800138000", "管理员", "手机用户", admin)) {
            assertThat(text).doesNotContain(secret);
        }
        assertThat(text).doesNotContainPattern("[a-f0-9]{32}:[a-f0-9]{128}");
        assertThat(LegacySource.sha256(source)).isEqualTo(before);
    }

    @Test
    void blockedUsersStayBlockedAndCannotLogIn() throws Exception {
        Path source = dir.resolve("hub.sqlite");
        try (LegacyFixture legacy = LegacyFixture.create(source)) {
            String admin = legacy.user("root@example.test", "Root", "root-pass-1", true, CREATED);
            String phoneOnly = legacy.user(null, "Phone", "phone-pass-1", false, CREATED);
            legacy.phone(phoneOnly, "+8613700137000", 1);
            String blocked = legacy.user("blocked@example.test", "Blocked", "blocked-pass-1", false, CREATED);
            legacy.block(blocked, admin, "", 1);
        }
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
        Path known = dir.resolve("known.json");
        // 把被封禁账号误标为“应能登录”：核对必须失败。
        Files.writeString(known, """
                [{"identifier":"root@example.test","password":"root-pass-1","expect":"login"},
                 {"identifier":"13700137000","password":"phone-pass-1","expect":"login"},
                 {"identifier":"blocked@example.test","password":"blocked-pass-1","expect":"login"}]
                """);
        Run verify = run("verify", "--source", source.toString(), "--known-passwords", known.toString());
        assertThat(verify.exit()).isEqualTo(1);
        assertThat(codes(verify.json())).containsExactly("LOGIN_CHECK_FAILED");
        assertThat(verify.json().path("logins").path("checks").get(2).path("actual").asString()).isEqualTo("rejected");
    }

    @Test
    void nodeGenerationSchemaWithRequiredEmailIsAccepted() throws Exception {
        Path source = dir.resolve("node.sqlite");
        try (LegacyFixture legacy = LegacyFixture.nodeGeneration(source)) {
            legacy.user("node@example.test", "Node", "node-pass-1", false, "2025-12-31T23:59:59.999Z");
        }
        Run inspect = run("inspect", "--source", source.toString());
        assertThat(inspect.exit()).isZero();
        assertThat(inspect.json().path("source").path("usersEmailNullable").asBoolean()).isFalse();
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
    }

    // ------------------------------------------------------------------ 冲突与无法迁移的记录

    @Test
    void emailsThatDifferOnlyByCaseAreReportedAndBlockMigration() throws Exception {
        Path source = dir.resolve("hub.sqlite");
        try (LegacyFixture legacy = LegacyFixture.create(source)) {
            legacy.user("Alice@Example.test", "A1", "pass-1-aaaa", false, CREATED);
            legacy.user("alice@example.test", "A2", "pass-2-aaaa", false, CREATED);
            legacy.user("josé@example.test", "J1", "pass-3-aaaa", false, CREATED);
            legacy.user("jose@example.test", "J2", "pass-4-aaaa", false, CREATED);
        }
        Run inspect = run("inspect", "--source", source.toString());
        assertThat(inspect.exit()).isEqualTo(1);
        assertThat(codes(inspect.json()).stream().filter("EMAIL_COLLISION_CI"::equals)).hasSize(2);

        Run migrate = run("migrate", "--source", source.toString());
        assertThat(migrate.exit()).isEqualTo(1);
        assertThat(migrate.json().path("migrated").asInt()).isZero();
        assertThat(count("account_user")).isZero();
    }

    @Test
    void unmigratableRecordsAreReportedNotGuessed() throws Exception {
        Path source = dir.resolve("hub.sqlite");
        try (LegacyFixture legacy = LegacyFixture.create(source)) {
            legacy.user("long@example.test", "名".repeat(65), "pass-1-aaaa", false, CREATED);
            legacy.user("time@example.test", "Time", "pass-2-aaaa", false, "yesterday");
            String badPhone = legacy.user(null, "BadPhone", "pass-3-aaaa", false, CREATED);
            legacy.phone(badPhone, "13800138000", 0);
            legacy.user(null, "Nobody", "pass-4-aaaa", false, CREATED);
            legacy.user("hash@example.test", "BadHash", null, false, CREATED);
        }
        Run inspect = run("inspect", "--source", source.toString());
        assertThat(inspect.exit()).isEqualTo(1);
        assertThat(codes(inspect.json())).contains("NAME_TOO_LONG", "TIMESTAMP_UNPARSABLE", "PHONE_FORMAT_INVALID",
                "NO_LOGIN_IDENTIFIER", "PASSWORD_FORMAT_INVALID");
        assertThat(run("migrate", "--source", source.toString()).exit()).isEqualTo(1);
        assertThat(count("account_user")).isZero();
    }

    @Test
    void unknownLegacySchemaIsRejected() throws Exception {
        Path source = dir.resolve("hub.sqlite");
        LegacyFixture.withUnknownColumn(source).close();
        Run inspect = run("inspect", "--source", source.toString());
        assertThat(inspect.exit()).isEqualTo(1);
        assertThat(inspect.err()).contains("SCHEMA_UNKNOWN");
    }

    // ------------------------------------------------------------------ 幂等与回滚

    @Test
    void secondMigrationIsRejectedAndChangesNothing() throws Exception {
        Path source = sampleSource();
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
        long users = count("account_user");

        Run again = run("migrate", "--source", source.toString());
        assertThat(again.exit()).isEqualTo(1);
        assertThat(again.err()).contains("TARGET_NOT_EMPTY");
        assertThat(count("account_user")).isEqualTo(users);
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        Path source = sampleSource();
        Run dryRun = run("migrate", "--source", source.toString(), "--dry-run");
        assertThat(dryRun.exit()).as(dryRun.err()).isZero();
        assertThat(dryRun.json().path("mode").asString()).isEqualTo("migrate --dry-run");
        assertThat(dryRun.json().path("migrated").asInt()).isEqualTo(3);
        assertThat(dryRun.json().path("committed").asBoolean()).isFalse();
        for (String table : TargetDatabase.ACCOUNT_TABLES) assertThat(count(table)).as(table).isZero();
    }

    @Test
    void failureHalfwayRollsBackEverything() throws Exception {
        Path source = sampleSource();
        AccountPlan plan = AccountPlan.build(LegacySource.read(source));
        try (TargetDatabase target = TargetDatabase.open(targetUrl, "root", MYSQL.getPassword())) {
            assertThatThrownBy(() -> new Migrator(target).failAfter(2).migrate(plan, false))
                    .isInstanceOfSatisfying(MigrationException.class,
                            error -> assertThat(error.code()).isEqualTo("MIGRATION_FAILED"));
        }
        for (String table : TargetDatabase.ACCOUNT_TABLES) assertThat(count(table)).as(table).isZero();
        // 回滚后可以重新完整迁移。
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
        assertThat(count("account_user")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 核对

    @Test
    void verifyDetectsChangedFieldsAndIncompleteLoginSamples() throws Exception {
        Path source = sampleSource();
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
        try (Connection target = target(); Statement statement = target.createStatement()) {
            statement.executeUpdate("UPDATE account_user SET name = 'changed' WHERE email = 'a@example.test'");
        }
        Path known = dir.resolve("known.json");
        Files.writeString(known, "[{\"identifier\":\"a@example.test\",\"password\":\"a-pass-111\",\"expect\":\"login\"}]");
        Run verify = run("verify", "--source", source.toString(), "--known-passwords", known.toString());
        assertThat(verify.exit()).isEqualTo(1);
        assertThat(codes(verify.json())).containsExactlyInAnyOrder("VERIFY_MISMATCH", "LOGIN_SAMPLE_INCOMPLETE");
        assertThat(verify.out()).contains("字段不一致：name");

        Run withoutSamples = run("verify", "--source", source.toString());
        assertThat(codes(withoutSamples.json())).contains("LOGIN_SAMPLE_INCOMPLETE");
    }

    @Test
    void httpLoginCheckPostsToTheV2LoginEndpoint() throws Exception {
        Path source = sampleSource();
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestHeaders().getFirst("Origin") + " " + body);
            int status = body.contains("a-pass-111") || body.contains("p-pass-111") ? 200 : 401;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            Path known = dir.resolve("known.json");
            Files.writeString(known, """
                    [{"identifier":"a@example.test","password":"a-pass-111","expect":"login"},
                     {"identifier":"13600136000","password":"p-pass-111","expect":"login"},
                     {"identifier":"b@example.test","password":"b-pass-111","expect":"blocked"}]
                    """);
            Run verify = run("verify", "--source", source.toString(), "--known-passwords", known.toString(),
                    "--login-url", url);
            assertThat(verify.exit()).as(verify.out()).isZero();
            assertThat(verify.json().path("logins").path("mode").asString()).isEqualTo("http");
            assertThat(requests).hasSize(3).allMatch(request -> request.startsWith(url + " {"));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ 默认额度（待 L5.1）

    @Test
    void defaultCreditIsAnOptionalStepPendingL51() throws Exception {
        Path source = sampleSource();
        assertThat(run("migrate", "--source", source.toString()).exit()).isZero();
        Run grant = run("grant-default-credit");
        assertThat(grant.exit()).isEqualTo(3);
        assertThat(grant.err()).contains("待 L5.1");
        JsonNode section = grant.json().path("aiDefaultCredit");
        assertThat(section.path("status").asString()).isEqualTo("pending-L5.1");
        assertThat(section.path("amountUnits").asLong()).isEqualTo(10_000);
        assertThat(section.path("users").asInt()).isEqualTo(3);
        assertThat(section.path("aiTablesPresent").asBoolean()).isFalse();
    }

    @Test
    void usageErrorsAndMissingTargetPasswordAreReported() throws Exception {
        assertThat(run().exit()).isEqualTo(2);
        assertThat(run("inspect", "--unknown").exit()).isEqualTo(2);
        assertThat(run("verify", "--dry-run").exit()).isEqualTo(2);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = MigrationCli.run(new String[] {"migrate", "--source", sampleSource().toString(), "--target-url",
                targetUrl, "--target-user", "root"}, Map.of(), System.out, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("TARGET_UNREACHABLE");
    }

    // ------------------------------------------------------------------ 辅助

    record Run(int exit, String out, String err) {
        JsonNode json() {
            return JSON.readTree(out.isBlank() ? "{}" : out);
        }
    }

    /** 3 个账号：邮箱 a（可登录）、只有手机号 p、被封禁的 b。 */
    private Path sampleSource() throws Exception {
        Path source = dir.resolve("sample-" + DATABASES.incrementAndGet() + ".sqlite");
        try (LegacyFixture legacy = LegacyFixture.create(source)) {
            String a = legacy.user("a@example.test", "A", "a-pass-111", true, CREATED);
            String p = legacy.user(null, "P", "p-pass-111", false, CREATED);
            legacy.phone(p, "+8613600136000", 1);
            String b = legacy.user("b@example.test", "B", "b-pass-111", false, CREATED);
            legacy.block(b, a, "spam", 2);
        }
        return source;
    }

    private Run run(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        if (args.length > 0 && !args[0].equals("inspect") && !all.contains("--target-url")) {
            all.addAll(List.of("--target-url", targetUrl, "--target-user", "root"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = MigrationCli.run(all.toArray(String[]::new), Map.of(MigrationCli.PASSWORD_ENV, MYSQL.getPassword()),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Connection target() throws Exception {
        return DriverManager.getConnection(targetUrl, "root", MYSQL.getPassword());
    }

    private long count(String table) throws Exception {
        try (Connection target = target(); Statement statement = target.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static List<String> strings(Statement statement, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }

    private static List<String> codes(JsonNode report) {
        List<String> codes = new ArrayList<>();
        report.path("findings").forEach(finding -> codes.add(finding.path("code").asString()));
        return codes;
    }
}
