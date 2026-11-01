package cloud.treenewbee.quizforge.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 的 MySQL。迁移只写账号四张表；所有时间按 UTC 的 DATETIME(3) 读写，不依赖连接时区。
 */
public final class TargetDatabase implements AutoCloseable {

    /** 迁移前必须为空的表（详细设计 09 第 5.3 节）。 */
    static final List<String> ACCOUNT_TABLES = List.of(
            "account_user", "account_user_phone", "account_unverified_contact", "account_block", "auth_session");
    /** 需要 V9（verified_at、account_unverified_contact）及之后的结构。 */
    static final int MIN_SCHEMA_VERSION = 9;

    private final Connection connection;

    private TargetDatabase(Connection connection) {
        this.connection = connection;
    }

    public static TargetDatabase open(String url, String user, String password) {
        try {
            Connection connection = DriverManager.getConnection(url, user, password);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return new TargetDatabase(connection);
        } catch (SQLException error) {
            throw new MigrationException("TARGET_UNREACHABLE", "无法连接 V2 数据库：" + error.getMessage());
        }
    }

    Connection connection() {
        return connection;
    }

    public String databaseName() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** Flyway 已成功执行的最高版本。 */
    public int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL")) {
            int max = 0;
            while (rows.next()) max = Math.max(max, Integer.parseInt(rows.getString(1)));
            return max;
        } catch (SQLException error) {
            throw new MigrationException("TARGET_SCHEMA_OUTDATED", "目标库没有 Flyway 记录，请先启动一次 V2 完成建表");
        }
    }

    public Map<String, Long> rowCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            for (String table : ACCOUNT_TABLES) {
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    counts.put(table, rows.getLong(1));
                }
            }
        }
        return counts;
    }

    void insert(AccountPlan.Account account) throws SQLException {
        try (PreparedStatement user = connection.prepareStatement("""
                INSERT INTO account_user
                    (id, email, name, password_hash, password_algo, is_admin, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """)) {
            user.setString(1, account.id());
            user.setString(2, account.email());
            user.setString(3, account.name());
            user.setString(4, account.passwordHash());
            user.setString(5, AccountPlan.PASSWORD_ALGO);
            user.setBoolean(6, account.admin());
            user.setObject(7, utc(account.createdAt()));
            user.setObject(8, utc(account.createdAt()));
            user.executeUpdate();
        }
        if (account.phone() != null) {
            try (PreparedStatement phone = connection.prepareStatement(
                    "INSERT INTO account_user_phone (user_id, phone, verified, verified_at) VALUES (?, ?, ?, ?)")) {
                phone.setString(1, account.id());
                phone.setString(2, account.phone().phone());
                phone.setBoolean(3, account.phone().verified());
                phone.setObject(4, utc(account.phone().verifiedAt()));
                phone.executeUpdate();
            }
        }
        for (var marker : account.unverified()) {
            try (PreparedStatement unverified = connection.prepareStatement(
                    "INSERT INTO account_unverified_contact (user_id, channel, contact) VALUES (?, ?, ?)")) {
                unverified.setString(1, account.id());
                unverified.setString(2, marker.channel());
                unverified.setString(3, marker.contact());
                unverified.executeUpdate();
            }
        }
        if (account.block() != null) {
            try (PreparedStatement block = connection.prepareStatement(
                    "INSERT INTO account_block (user_id, actor_id, reason, created_at) VALUES (?, ?, ?, ?)")) {
                block.setString(1, account.id());
                block.setString(2, account.block().actorId());
                block.setString(3, account.block().reason());
                block.setObject(4, utc(account.block().createdAt()));
                block.executeUpdate();
            }
        }
    }

    /** 读出目标库中的全部账号，形状与 {@link AccountPlan.Account} 相同，便于逐字段比较。 */
    public Map<String, AccountPlan.Account> readAccounts() throws SQLException {
        Map<String, AccountPlan.Phone> phones = new HashMap<>();
        Map<String, List<AccountPlan.Unverified>> unverified = new HashMap<>();
        Map<String, AccountPlan.Block> blocks = new HashMap<>();
        Map<String, String> algorithms = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT user_id, phone, verified, verified_at FROM account_user_phone")) {
                while (rows.next()) {
                    phones.put(rows.getString(1), new AccountPlan.Phone(rows.getString(2), rows.getBoolean(3),
                            instant(rows, 4)));
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT user_id, channel, contact FROM account_unverified_contact ORDER BY user_id, channel")) {
                while (rows.next()) {
                    unverified.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                            .add(new AccountPlan.Unverified(rows.getString(2), rows.getString(3)));
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT user_id, actor_id, reason, created_at FROM account_block")) {
                while (rows.next()) {
                    blocks.put(rows.getString(1), new AccountPlan.Block(rows.getString(2), rows.getString(3),
                            instant(rows, 4)));
                }
            }
            Map<String, AccountPlan.Account> accounts = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery("""
                    SELECT id, email, name, password_hash, password_algo, is_admin, created_at
                      FROM account_user ORDER BY id
                    """)) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    algorithms.put(id, rows.getString(5));
                    accounts.put(id, new AccountPlan.Account(id, rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getBoolean(6), instant(rows, 7), phones.get(id),
                            List.copyOf(unverified.getOrDefault(id, List.of())), blocks.get(id)));
                }
            }
            this.algorithms = algorithms;
            return accounts;
        }
    }

    private Map<String, String> algorithms = Map.of();

    /** 最近一次 {@link #readAccounts()} 读到的 password_algo。 */
    public String passwordAlgorithm(String userId) {
        return algorithms.get(userId);
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rows, int column) throws SQLException {
        LocalDateTime value = rows.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败不影响已提交或已回滚的结果。
        }
    }
}
