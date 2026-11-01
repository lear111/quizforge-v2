package cloud.treenewbee.quizforge.migration;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧平台账号迁移工具（L7，详细设计 09，ADR-008）。只迁移账号与密码；AI 余额、流水与兑换码不迁移。
 *
 * <pre>
 * inspect              --source 副本.sqlite [--report 报告.json]
 * migrate              --source 副本.sqlite --target-url jdbc:mysql://… [--target-user 用户] [--dry-run] [--report …]
 * verify               --source 副本.sqlite --target-url … [--known-passwords 文件.json] [--login-url http://…] [--report …]
 * grant-default-credit --target-url … [--report …]      （待 L5.1 完成后执行）
 * </pre>
 *
 * 目标库密码只从环境变量 QF_MIGRATION_TARGET_PASSWORD 读取，不出现在命令行与报告中。
 * 退出码：0 成功；1 有 ERROR 或执行失败（已回滚）；2 参数错误；3 步骤尚不可执行。
 */
public final class MigrationCli {

    static final String PASSWORD_ENV = "QF_MIGRATION_TARGET_PASSWORD";
    private static final Set<String> FLAGS = Set.of("--dry-run");
    private static final Set<String> OPTIONS = Set.of("--source", "--target-url", "--target-user", "--report",
            "--known-passwords", "--login-url");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MigrationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> options = new HashMap<>();
        String command;
        try {
            command = parse(args, options);
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            err.println("用法：inspect | migrate [--dry-run] | verify | grant-default-credit（详见 tools/migration/README.md）");
            return 2;
        }
        Instant started = Instant.now();
        try {
            return switch (command) {
                case "inspect" -> inspect(options, started, out);
                case "migrate" -> migrate(options, env, started, out);
                case "verify" -> verify(options, env, started, out);
                case "grant-default-credit" -> grantDefaultCredit(options, env, started, out, err);
                default -> throw new IllegalStateException(command);
            };
        } catch (MigrationException error) {
            err.println("[" + error.code() + "] " + error.getMessage());
            return 1;
        }
    }

    private static int inspect(Map<String, String> options, Instant started, PrintStream out) {
        LegacySource.Data data = LegacySource.read(source(options));
        AccountPlan plan = AccountPlan.build(data);
        Report report = new Report("inspect", started).source(data).findings(plan.findings())
                .accounts(plan, null, "planned")
                .put("aiDefaultCredit", DefaultCredit.reportSection(plan.accounts().size(), false));
        return finish(report, options, out);
    }

    private static int migrate(Map<String, String> options, Map<String, String> env, Instant started, PrintStream out) {
        boolean dryRun = options.containsKey("--dry-run");
        LegacySource.Data data = LegacySource.read(source(options));
        AccountPlan plan = AccountPlan.build(data);
        Report report = new Report(dryRun ? "migrate --dry-run" : "migrate", started).source(data)
                .findings(plan.findings());
        if (plan.hasErrors()) {
            report.accounts(plan, null, "not-migrated").put("migrated", 0);
            return finish(report, options, out);
        }
        try (TargetDatabase target = target(options, env)) {
            report.target(target.databaseName(), target.schemaVersion());
            Migrator.Result result = new Migrator(target).migrate(plan, dryRun);
            report.findings(result.findings()).put("migrated", result.migrated()).put("committed", result.committed());
            report.accounts(plan, result.committed() ? result.migrated() : null,
                    result.findings().isEmpty() ? (dryRun ? "dry-run-ok" : "ok") : "not-migrated");
            report.put("aiDefaultCredit", DefaultCredit.reportSection(plan.accounts().size(),
                    DefaultCredit.aiTablesPresent(target.connection())));
            return finish(report, options, out);
        } catch (SQLException error) {
            throw new MigrationException("TARGET_UNREACHABLE", "读取目标库失败：" + error.getMessage());
        }
    }

    private static int verify(Map<String, String> options, Map<String, String> env, Instant started, PrintStream out) {
        LegacySource.Data data = LegacySource.read(source(options));
        AccountPlan plan = AccountPlan.build(data);
        Report report = new Report("verify", started).source(data).findings(plan.findings());
        try (TargetDatabase target = target(options, env)) {
            report.target(target.databaseName(), target.schemaVersion());
            report.findings(Verifier.verify(plan, target));
            if (options.containsKey("--known-passwords")) {
                List<Verifier.LoginCheck> checks = Verifier.checkLogins(knownPasswords(options), target,
                        options.get("--login-url"));
                report.findings(Verifier.coverage(checks));
                report.put("logins", Map.of("mode", options.containsKey("--login-url") ? "http" : "offline",
                        "summary", Verifier.summary(checks), "checks", checks));
            } else {
                report.findings(List.of(Finding.error("LOGIN_SAMPLE_INCOMPLETE", null,
                        "未提供 --known-passwords：须用已知密码的邮箱账号与只有手机号的账号各至少一个确认能登录")));
            }
            report.accounts(plan, target.readAccounts().size(), "ok");
            report.put("aiDefaultCredit", DefaultCredit.reportSection(plan.accounts().size(),
                    DefaultCredit.aiTablesPresent(target.connection())));
            return finish(report, options, out);
        } catch (SQLException error) {
            throw new MigrationException("TARGET_UNREACHABLE", "读取目标库失败：" + error.getMessage());
        }
    }

    private static int grantDefaultCredit(Map<String, String> options, Map<String, String> env, Instant started,
                                          PrintStream out, PrintStream err) {
        try (TargetDatabase target = target(options, env)) {
            boolean present = DefaultCredit.aiTablesPresent(target.connection());
            int users = target.readAccounts().size();
            Report report = new Report("grant-default-credit", started)
                    .target(target.databaseName(), target.schemaVersion())
                    .put("aiDefaultCredit", DefaultCredit.reportSection(users, present));
            finish(report, options, out);
            // L5.1 接入 DefaultCredit.Granter 后，在这里于一个事务内为全部迁移用户发放并核对。
            err.println("默认额度待 L5.1 完成后执行：" + (present ? "AI 表已存在，但发放接口尚未接入本工具"
                    : "V2 尚无 AI 余额与流水表") + "（见 DefaultCredit 的接口说明）");
            return 3;
        } catch (SQLException error) {
            throw new MigrationException("TARGET_UNREACHABLE", "读取目标库失败：" + error.getMessage());
        }
    }

    private static int finish(Report report, Map<String, String> options, PrintStream out) {
        var result = report.finish(Instant.now());
        if (options.containsKey("--report")) Report.write(result, Path.of(options.get("--report")));
        out.println(Report.json(result));
        return report.hasErrors() ? 1 : 0;
    }

    private static Path source(Map<String, String> options) {
        String value = options.get("--source");
        if (value == null) throw new MigrationException("USAGE", "缺少 --source（旧库副本路径）");
        return Path.of(value);
    }

    private static TargetDatabase target(Map<String, String> options, Map<String, String> env) {
        String url = options.get("--target-url");
        if (url == null) throw new MigrationException("USAGE", "缺少 --target-url（V2 的 JDBC 地址）");
        String password = env.getOrDefault(PASSWORD_ENV, "");
        return TargetDatabase.open(url, options.getOrDefault("--target-user", "quizforge"), password);
    }

    private static List<Verifier.KnownPassword> knownPasswords(Map<String, String> options) {
        try {
            return new ArrayList<>(JSON.readValue(Files.readString(Path.of(options.get("--known-passwords"))),
                    new TypeReference<List<Verifier.KnownPassword>>() { }));
        } catch (IOException | RuntimeException error) {
            throw new MigrationException("KNOWN_PASSWORDS_UNREADABLE",
                    "无法读取已知密码文件（JSON 数组：identifier、password、expect）");
        }
    }

    private static String parse(String[] args, Map<String, String> options) {
        if (args.length == 0) throw new IllegalArgumentException("缺少命令");
        String command = args[0];
        if (!Set.of("inspect", "migrate", "verify", "grant-default-credit").contains(command)) {
            throw new IllegalArgumentException("未知命令：" + command);
        }
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            if (FLAGS.contains(key)) {
                options.put(key, "true");
            } else if (OPTIONS.contains(key) && i + 1 < args.length) {
                options.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("无法识别的参数：" + key);
            }
        }
        if (options.containsKey("--dry-run") && !command.equals("migrate")) {
            throw new IllegalArgumentException("--dry-run 只用于 migrate");
        }
        return command;
    }
}
