package cloud.treenewbee.quizforge.migration;

import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把旧库的账号数据转换为 V2 的行（详细设计 09 第 1.1 节），并列出所有发现。
 * 有 ERROR 时不得迁移；WARN 照常迁移但须作者签注。
 */
public final class AccountPlan {

    /** V2 的一个账号：account_user + account_user_phone + account_unverified_contact + account_block。 */
    public record Account(String id, String email, String name, String passwordHash, boolean admin,
                          Instant createdAt, Phone phone, List<Unverified> unverified, Block block) {
    }

    public record Phone(String phone, boolean verified, Instant verifiedAt) {
    }

    public record Unverified(String channel, String contact) {
    }

    public record Block(String actorId, String reason, Instant createdAt) {
    }

    static final String PASSWORD_ALGO = "scrypt-v1";
    static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern PASSWORD_FORMAT = Pattern.compile("[a-f0-9]{32}:[a-f0-9]{128}");
    static final Pattern PHONE = Pattern.compile("\\+861[3-9][0-9]{9}");
    // 与 V2 Contact 的邮箱规则一致。
    static final Pattern EMAIL = Pattern.compile("[^\\s\\p{Z}\\uFEFF@]+@[^\\s\\p{Z}\\uFEFF@]+\\.[^\\s\\p{Z}\\uFEFF@]+");
    static final Pattern EDGE_WHITESPACE = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    static final int NAME_MAX = 64;
    static final int EMAIL_MAX = 254;
    static final int REASON_MAX = 500;

    private final List<Account> accounts;
    private final List<Finding> findings;

    private AccountPlan(List<Account> accounts, List<Finding> findings) {
        this.accounts = List.copyOf(accounts);
        this.findings = List.copyOf(findings);
    }

    public List<Account> accounts() {
        return accounts;
    }

    public List<Finding> findings() {
        return findings;
    }

    public boolean hasErrors() {
        return findings.stream().anyMatch(finding -> finding.severity() == Finding.Severity.ERROR);
    }

    public static AccountPlan build(LegacySource.Data data) {
        List<Finding> findings = new ArrayList<>();
        Set<String> ids = data.users().stream().map(LegacySource.User::id).collect(Collectors.toSet());
        Map<String, LegacySource.Phone> phones = new HashMap<>();
        for (var phone : data.phones()) {
            if (!ids.contains(phone.userId())) findings.add(Finding.error("ORPHAN_ROW", phone.userId(), "user_phones"));
            else phones.put(phone.userId(), phone);
        }
        Map<String, List<LegacySource.Unverified>> unverified = new HashMap<>();
        for (var row : data.unverified()) {
            if (!ids.contains(row.userId())) findings.add(Finding.error("ORPHAN_ROW", row.userId(), "unverified_contacts"));
            else unverified.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, LegacySource.Block> blocks = new HashMap<>();
        for (var block : data.blocks()) {
            if (!ids.contains(block.userId())) findings.add(Finding.error("ORPHAN_ROW", block.userId(), "user_blocks"));
            else blocks.put(block.userId(), block);
        }

        List<Account> accounts = new ArrayList<>();
        Map<String, String> emailOwners = new LinkedHashMap<>();
        for (var user : data.users()) {
            String id = user.id();
            if (id == null || !UUID.matcher(id).matches()) {
                findings.add(Finding.error("ID_FORMAT_INVALID", id, "用户 ID 不是小写 UUID"));
                continue;
            }
            String email = email(user, findings);
            if (email != null) {
                // V2 的排序规则 utf8mb4_0900_ai_ci 不区分大小写与重音：按同样规则检查冲突。
                String other = emailOwners.putIfAbsent(collationKey(email), id);
                if (other != null) {
                    findings.add(Finding.error("EMAIL_COLLISION_CI", id,
                            "邮箱与用户 " + Masking.id(other) + " 只差大小写或重音，V2 中无法同时存在"));
                }
            }
            String name = user.name();
            int nameLength = name == null ? 0 : name.codePointCount(0, name.length());
            if (nameLength == 0 || nameLength > NAME_MAX) {
                findings.add(Finding.error(nameLength == 0 ? "NAME_INVALID" : "NAME_TOO_LONG", id,
                        "昵称长度 " + nameLength + "（V2 为 1–64 个字符）"));
            }
            if (user.password() == null || !PASSWORD_FORMAT.matcher(user.password()).matches()) {
                findings.add(Finding.warn("PASSWORD_FORMAT_INVALID", id,
                        "密码存储值不符合 <salt>:<hash> 格式，旧系统同样无法登录；原样迁移"));
            }
            Instant created = parseCreated(user.created(), id, findings);
            Phone phone = phone(phones.get(id), findings);
            if (email == null && phone == null) {
                findings.add(Finding.warn("NO_LOGIN_IDENTIFIER", id, "既没有邮箱也没有手机号，新旧系统都无法登录"));
            }
            List<Unverified> markers = new ArrayList<>();
            for (var row : unverified.getOrDefault(id, List.of())) {
                String current = "email".equals(row.channel()) ? email : "phone".equals(row.channel()) && phone != null
                        ? phone.phone() : null;
                String contact = "email".equals(row.channel()) && row.contact() != null
                        ? row.contact().toLowerCase(Locale.ROOT) : row.contact();
                if (current != null && current.equals(contact)) {
                    markers.add(new Unverified(row.channel(), current));
                } else {
                    // 旧系统只认与当前联系方式一致的标记（AccountStore.profile），其余为历史残留。
                    findings.add(Finding.info("STALE_UNVERIFIED_CONTACT", id, "未验证标记与当前联系方式不一致，不迁移"));
                }
            }
            Block block = block(blocks.get(id), findings);
            accounts.add(new Account(id, email, name, user.password(), user.admin() != 0, created, phone,
                    List.copyOf(markers), block));
        }
        return new AccountPlan(accounts, findings);
    }

    private static String email(LegacySource.User user, List<Finding> findings) {
        if (user.email() == null) return null;
        String normalized = EDGE_WHITESPACE.matcher(user.email()).replaceAll("").toLowerCase(Locale.ROOT);
        if (normalized.length() > EMAIL_MAX || !EMAIL.matcher(normalized).matches()) {
            findings.add(Finding.error("EMAIL_INVALID", user.id(), "邮箱不符合 V2 的格式规则"));
            return normalized;
        }
        if (!normalized.equals(user.email())) {
            findings.add(Finding.warn("EMAIL_NOT_NORMALIZED", user.id(),
                    "邮箱含大写或首尾空白；按规范形式迁移，否则 V2 登录时查不到"));
        }
        return normalized;
    }

    private static Phone phone(LegacySource.Phone row, List<Finding> findings) {
        if (row == null) return null;
        if (row.phone() == null || !PHONE.matcher(row.phone()).matches()) {
            findings.add(Finding.error("PHONE_FORMAT_INVALID", row.userId(), "手机号不是 +86 加 11 位的规范形式"));
        }
        boolean verified = row.verified() != 0;
        return new Phone(row.phone(), verified, verified ? millis(row.verified()) : null);
    }

    private static Block block(LegacySource.Block row, List<Finding> findings) {
        if (row == null) return null;
        String reason = row.reason() == null ? "" : row.reason();
        if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
            findings.add(Finding.error("REASON_TOO_LONG", row.userId(), "封禁原因超过 500 个字符"));
        }
        return new Block(row.actorId(), reason, millis(row.created()));
    }

    private static Instant parseCreated(String raw, String id, List<Finding> findings) {
        try {
            return Instant.parse(raw).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException | NullPointerException error) {
            findings.add(Finding.error("TIMESTAMP_UNPARSABLE", id, "users.created 不是 ISO-8601 时间"));
            return Instant.EPOCH;
        }
    }

    static Instant millis(long value) {
        return Instant.ofEpochMilli(value);
    }

    /** 近似 utf8mb4_0900_ai_ci 的比较键：去掉重音并忽略大小写。 */
    static String collationKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
