package cloud.treenewbee.quizforge.migration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

/**
 * 迁移后核对（详细设计 09 第 4.3、4.5 节）：逐用户比较全部账号字段；用一组已知密码的测试账号
 * 确认原密码能在 V2 登录、被封禁的账号不能登录。
 */
public final class Verifier {

    /** 一个已知密码的测试账号；expect 为 login（应能登录）或 blocked（被封禁，应被拒绝）。 */
    public record KnownPassword(String identifier, String password, String expect) {
    }

    /**
     * 一次登录检查的结果，报告中只出现脱敏账号。kind 为 email（有邮箱的账号用邮箱登录）、
     * phone-only（只有手机号的账号）、phone（同时有邮箱的账号用手机号登录）或 unknown（目标库中找不到）。
     */
    public record LoginCheck(String account, String kind, String expect, String actual, boolean passed) {
    }

    private static final Pattern PHONE_PREFIX = Pattern.compile("^(?:\\+86|86)(?=1\\d{10}$)");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Verifier() {
    }

    /** 比较计划与目标库：字段不一致、缺少或多出的账号都列为发现。 */
    static List<Finding> compare(AccountPlan plan, Map<String, AccountPlan.Account> target, TargetDatabase database) {
        List<Finding> findings = new ArrayList<>();
        for (var expected : plan.accounts()) {
            var actual = target.get(expected.id());
            if (actual == null) {
                findings.add(Finding.error("VERIFY_MISMATCH", expected.id(), "目标库缺少该账号"));
                continue;
            }
            for (String field : differences(expected, actual)) {
                findings.add(Finding.error("VERIFY_MISMATCH", expected.id(), "字段不一致：" + field));
            }
            if (!AccountPlan.PASSWORD_ALGO.equals(database.passwordAlgorithm(expected.id()))) {
                findings.add(Finding.error("VERIFY_MISMATCH", expected.id(), "字段不一致：password_algo"));
            }
        }
        var expectedIds = plan.accounts().stream().map(AccountPlan.Account::id).toList();
        for (String id : target.keySet()) {
            if (!expectedIds.contains(id)) {
                findings.add(Finding.warn("TARGET_EXTRA_ACCOUNT", id, "目标库有旧库中不存在的账号（迁移后新注册？）"));
            }
        }
        return findings;
    }

    static List<String> differences(AccountPlan.Account expected, AccountPlan.Account actual) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(expected.email(), actual.email())) fields.add("email");
        if (!Objects.equals(expected.name(), actual.name())) fields.add("name");
        if (!Objects.equals(expected.passwordHash(), actual.passwordHash())) fields.add("password_hash");
        if (expected.admin() != actual.admin()) fields.add("is_admin");
        if (!Objects.equals(expected.createdAt(), actual.createdAt())) fields.add("created_at");
        if (!Objects.equals(expected.phone(), actual.phone())) fields.add("phone");
        if (!Objects.equals(expected.unverified(), actual.unverified())) fields.add("unverified_contacts");
        if (!Objects.equals(expected.block(), actual.block())) fields.add("block");
        return fields;
    }

    public static List<Finding> verify(AccountPlan plan, TargetDatabase target) {
        try {
            return compare(plan, target.readAccounts(), target);
        } catch (SQLException error) {
            throw new MigrationException("TARGET_UNREACHABLE", "读取目标库失败：" + error.getMessage());
        }
    }

    /**
     * 用已知密码检查登录。loginUrl 为空时离线核对（与 V2 相同的 scrypt 算法 + 封禁状态）；
     * 给出 V2 地址时向 {@code POST /api/auth/login} 发起真实登录（200 为成功，401 为拒绝）。
     */
    public static List<LoginCheck> checkLogins(List<KnownPassword> known, TargetDatabase target, String loginUrl) {
        Map<String, AccountPlan.Account> accounts;
        try {
            accounts = target.readAccounts();
        } catch (SQLException error) {
            throw new MigrationException("TARGET_UNREACHABLE", "读取目标库失败：" + error.getMessage());
        }
        List<LoginCheck> checks = new ArrayList<>();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (var entry : known) {
                String identifier = normalize(entry.identifier());
                String expect = "blocked".equals(entry.expect()) ? "blocked" : "login";
                String actual = loginUrl == null || loginUrl.isBlank()
                        ? offline(identifier, entry.password(), accounts)
                        : online(http, loginUrl, entry.identifier(), entry.password());
                String masked = identifier.contains("@") ? Masking.email(identifier) : Masking.phone(identifier);
                boolean passed = expect.equals("login") ? actual.equals("login") : actual.equals("rejected");
                checks.add(new LoginCheck(masked, kind(identifier, accounts), expect, actual, passed));
            }
        }
        return checks;
    }

    private static String kind(String identifier, Map<String, AccountPlan.Account> accounts) {
        if (identifier.contains("@")) {
            return accounts.values().stream().anyMatch(row -> identifier.equals(row.email())) ? "email" : "unknown";
        }
        return accounts.values().stream()
                .filter(row -> row.phone() != null && identifier.equals(row.phone().phone()))
                .map(row -> row.email() == null ? "phone-only" : "phone")
                .findFirst().orElse("unknown");
    }

    /** 抽查须至少包含一个能登录的邮箱账号和一个能登录的只有手机号的账号（验收场景 11）。 */
    static List<Finding> coverage(List<LoginCheck> checks) {
        List<Finding> findings = new ArrayList<>();
        for (var check : checks) {
            if (!check.passed()) {
                findings.add(Finding.error("LOGIN_CHECK_FAILED", null, check.account() + " 预期 " + check.expect()
                        + "，实际 " + check.actual()));
            }
        }
        for (String kind : List.of("email", "phone-only")) {
            boolean covered = checks.stream().anyMatch(check -> check.kind().equals(kind)
                    && check.expect().equals("login") && check.passed());
            if (!covered) {
                findings.add(Finding.error("LOGIN_SAMPLE_INCOMPLETE", null,
                        "已知密码的抽查缺少能登录的" + (kind.equals("email") ? "邮箱账号" : "只有手机号的账号")));
            }
        }
        return findings;
    }

    private static String offline(String identifier, String password, Map<String, AccountPlan.Account> accounts) {
        Optional<AccountPlan.Account> account = accounts.values().stream()
                .filter(row -> identifier.equals(row.email())
                        || row.phone() != null && identifier.equals(row.phone().phone()))
                .findFirst();
        if (account.isEmpty() || !LegacyPassword.matches(password, account.get().passwordHash())) return "rejected";
        return account.get().block() != null ? "rejected" : "login";
    }

    private static String online(HttpClient http, String loginUrl, String identifier, String password) {
        try {
            URI base = URI.create(loginUrl);
            String origin = base.getScheme() + "://" + base.getRawAuthority();
            String body = JSON.writeValueAsString(Map.of("identifier", identifier, "password", password));
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Origin", origin)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? "login" : status == 401 ? "rejected" : "http-" + status;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return "unreachable";
        }
    }

    /** 与 V2 Contact 的规范化一致：邮箱转小写；手机号去空格、横线与 +86 前缀。 */
    static String normalize(String raw) {
        String value = AccountPlan.EDGE_WHITESPACE.matcher(raw == null ? "" : raw).replaceAll("");
        if (value.contains("@")) return value.toLowerCase(Locale.ROOT);
        String digits = PHONE_PREFIX.matcher(value.replace(" ", "").replace("-", "")).replaceFirst("");
        return "+86" + digits;
    }

    static Map<String, Object> summary(List<LoginCheck> checks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", checks.size());
        result.put("passed", checks.stream().filter(LoginCheck::passed).count());
        result.put("emailAccounts", checks.stream().filter(check -> check.kind().equals("email")).count());
        result.put("phoneOnlyAccounts", checks.stream().filter(check -> check.kind().equals("phone-only")).count());
        result.put("blockedAccounts", checks.stream().filter(check -> check.expect().equals("blocked")).count());
        return result;
    }
}
