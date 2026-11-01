package cloud.treenewbee.quizforge.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * report.json（详细设计 09 第 4.4 节）。个人信息一律脱敏：ID 前 8 位、昵称首字、
 * a***@example.com、+86138****8000；不含密码哈希。报告应写在 git 忽略的 tools/migration/local/ 下。
 */
public final class Report {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Map<String, Object> root = new LinkedHashMap<>();
    private final List<Finding> findings = new ArrayList<>();

    Report(String mode, Instant startedAt) {
        root.put("mode", mode);
        root.put("toolVersion", toolVersion());
        root.put("startedAt", startedAt.toString());
    }

    Report source(LegacySource.Data data) {
        root.put("source", Map.of("sha256", data.sha256(), "usersEmailNullable", data.emailNullable()));
        return this;
    }

    Report target(String database, int flywayVersion) {
        root.put("target", Map.of("database", database, "flywayVersion", flywayVersion));
        return this;
    }

    Report findings(List<Finding> values) {
        findings.addAll(values);
        return this;
    }

    Report put(String key, Object value) {
        root.put(key, value);
        return this;
    }

    /**
     * 汇总与逐用户条目。targetUsers 为 null 表示未读取目标库（inspect）；mismatched 为核对不一致的用户 ID 前缀。
     */
    Report accounts(AccountPlan plan, Integer targetUsers, String checkState) {
        Set<String> problems = findings.stream()
                .filter(finding -> finding.severity() == Finding.Severity.ERROR && finding.userId() != null)
                .map(Finding::userId).collect(Collectors.toSet());
        Map<String, Object> users = new LinkedHashMap<>();
        users.put("source", plan.accounts().size());
        if (targetUsers != null) users.put("target", targetUsers);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", users);
        summary.put("emailAccounts", plan.accounts().stream().filter(a -> a.email() != null).count());
        summary.put("phoneOnly", plan.accounts().stream().filter(a -> a.email() == null && a.phone() != null).count());
        summary.put("blocked", plan.accounts().stream().filter(a -> a.block() != null).count());
        summary.put("admins", plan.accounts().stream().filter(AccountPlan.Account::admin).count());
        root.put("summary", summary);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (var account : plan.accounts()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", Masking.id(account.id()));
            entry.put("label", Masking.name(account.name()));
            entry.put("email", Masking.email(account.email()));
            entry.put("phone", account.phone() == null ? null : Masking.phone(account.phone().phone()));
            entry.put("admin", account.admin());
            entry.put("blocked", account.block() != null);
            String state = problems.contains(Masking.id(account.id())) ? "error" : checkState;
            entry.put("checks", Map.of("identity", state, "password", state,
                    "phone", account.phone() == null ? "n/a" : state, "block", account.block() == null ? "n/a" : state));
            entries.add(entry);
        }
        root.put("users", entries);
        return this;
    }

    boolean hasErrors() {
        return findings.stream().anyMatch(finding -> finding.severity() == Finding.Severity.ERROR);
    }

    Map<String, Object> finish(Instant finishedAt) {
        root.put("finishedAt", finishedAt.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) root.computeIfAbsent("summary", key -> new LinkedHashMap<>());
        for (var severity : Finding.Severity.values()) {
            String key = switch (severity) {
                case ERROR -> "errors";
                case WARN -> "warnings";
                case INFO -> "infos";
            };
            summary.put(key, findings.stream().filter(finding -> finding.severity() == severity).count());
        }
        root.put("findings", findings);
        return root;
    }

    /** 写入不存在的文件（不覆盖已有报告）。 */
    static void write(Map<String, Object> report, Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(report) + "\n", StandardOpenOption.CREATE_NEW);
        } catch (IOException error) {
            throw new MigrationException("REPORT_UNWRITABLE", "无法写入报告（文件已存在或目录不可写）：" + file);
        }
    }

    static String json(Map<String, Object> report) {
        return JSON.writeValueAsString(report);
    }

    private static String toolVersion() {
        String version = Report.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
