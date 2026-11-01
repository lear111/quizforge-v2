package cloud.treenewbee.quizforge.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sqlite.SQLiteConfig;

/**
 * 以只读方式打开旧平台 Hub 数据库的<strong>副本</strong>，读出账号相关的四张表。
 *
 * <p>Node Hub 与 Java Hub 的账号表结构相同，唯一差别是旧 Node 库的 {@code users.email} 为 NOT NULL
 * （详细设计 09 第 0 节）。出现未知列或缺少必需列时拒绝，不猜测含义。
 */
public final class LegacySource {

    public record User(String id, String email, String name, String password, long admin, String created) {
    }

    public record Phone(String userId, String phone, long verified) {
    }

    public record Unverified(String userId, String channel, String contact) {
    }

    public record Block(String userId, String actorId, String reason, long created) {
    }

    public record Data(List<User> users, List<Phone> phones, List<Unverified> unverified, List<Block> blocks,
                       boolean emailNullable, String sha256) {
    }

    /** 每张表必须恰好包含的列（与旧 schema.sql、legacy-hub.sql 一致）。 */
    static final Map<String, Set<String>> TABLES = Map.of(
            "users", Set.of("id", "email", "name", "password", "admin", "created"),
            "user_phones", Set.of("user_id", "phone", "verified"),
            "unverified_contacts", Set.of("user_id", "channel", "contact"),
            "user_blocks", Set.of("user_id", "actor_id", "reason", "created"));

    private LegacySource() {
    }

    /**
     * 读取副本；结构不符或完整性检查失败时抛出 {@link MigrationException}。
     * 读取前后各计算一次文件 SHA-256，不一致说明副本在读取期间被修改。
     */
    public static Data read(Path file) {
        if (!Files.isRegularFile(file)) throw new MigrationException("SOURCE_MISSING", "旧库副本不存在：" + file);
        String before = sha256(file);
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enforceForeignKeys(false);
        String url = "jdbc:sqlite:file:" + file.toAbsolutePath().toString().replace('\\', '/') + "?mode=ro";
        Data data;
        try (Connection connection = config.createConnection(url)) {
            checkIntegrity(connection);
            boolean emailNullable = checkSchema(connection);
            data = new Data(users(connection), phones(connection), unverified(connection), blocks(connection),
                    emailNullable, before);
        } catch (SQLException error) {
            throw new MigrationException("SOURCE_UNREADABLE", "无法读取旧库副本：" + error.getMessage());
        }
        if (!before.equals(sha256(file))) {
            throw new MigrationException("SOURCE_CHANGED", "读取期间旧库副本被修改，请使用停写后的副本");
        }
        return data;
    }

    private static void checkIntegrity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) {
                    throw new MigrationException("INTEGRITY_FAILED", "旧库副本 integrity_check 未通过");
                }
            }
            try (ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw new MigrationException("INTEGRITY_FAILED", "旧库副本 foreign_key_check 未通过");
            }
        }
    }

    /** 返回 users.email 是否可空（Java Hub 已升级）；未知结构直接拒绝。 */
    private static boolean checkSchema(Connection connection) throws SQLException {
        boolean emailNullable = false;
        for (var table : TABLES.entrySet()) {
            Set<String> columns = new LinkedHashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table.getKey() + ")")) {
                while (rows.next()) {
                    columns.add(rows.getString("name"));
                    if (table.getKey().equals("users") && rows.getString("name").equals("email")) {
                        emailNullable = rows.getInt("notnull") == 0;
                    }
                }
            }
            if (columns.isEmpty()) {
                throw new MigrationException("SCHEMA_UNKNOWN", "旧库缺少表 " + table.getKey());
            }
            if (!columns.equals(table.getValue())) {
                throw new MigrationException("SCHEMA_UNKNOWN", "旧库表 " + table.getKey() + " 的列与已知结构不一致："
                        + columns);
            }
        }
        return emailNullable;
    }

    private static List<User> users(Connection connection) throws SQLException {
        List<User> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, email, name, password, admin, created FROM users ORDER BY id")) {
            while (rows.next()) {
                result.add(new User(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getLong(5), rows.getString(6)));
            }
        }
        return result;
    }

    private static List<Phone> phones(Connection connection) throws SQLException {
        List<Phone> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT user_id, phone, verified FROM user_phones ORDER BY user_id")) {
            while (rows.next()) result.add(new Phone(rows.getString(1), rows.getString(2), rows.getLong(3)));
        }
        return result;
    }

    private static List<Unverified> unverified(Connection connection) throws SQLException {
        List<Unverified> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT user_id, channel, contact FROM unverified_contacts ORDER BY user_id, channel")) {
            while (rows.next()) result.add(new Unverified(rows.getString(1), rows.getString(2), rows.getString(3)));
        }
        return result;
    }

    private static List<Block> blocks(Connection connection) throws SQLException {
        List<Block> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT user_id, actor_id, reason, created FROM user_blocks ORDER BY user_id")) {
            while (rows.next()) {
                result.add(new Block(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4)));
            }
        }
        return result;
    }

    static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new MigrationException("SOURCE_UNREADABLE", "无法读取旧库副本：" + error.getMessage());
        }
    }
}
