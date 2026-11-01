package cloud.treenewbee.quizforge.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import org.bouncycastle.crypto.generators.SCrypt;

/**
 * 在测试中现场构造的合成旧库（只含合成账号，不使用真实数据）。表结构取自旧平台
 * java-hub/src/main/resources/schema.sql 与 test/fixtures/legacy-hub.sql 的账号相关表，
 * 另加几张不迁移的表以模拟完整的旧库。
 */
final class LegacyFixture implements AutoCloseable {

    private final Connection connection;
    final Path file;

    private LegacyFixture(Path file, boolean emailNullable, String extraUserColumn) throws SQLException {
        this.file = file;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE users (id TEXT PRIMARY KEY, email TEXT UNIQUE" + (emailNullable ? "" : " NOT NULL")
                    + ", name TEXT NOT NULL, password TEXT NOT NULL, admin INTEGER NOT NULL DEFAULT 0, created TEXT NOT NULL"
                    + (extraUserColumn == null ? "" : ", " + extraUserColumn + " TEXT") + ")");
            statement.execute("CREATE TABLE sessions (token TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires INTEGER NOT NULL)");
            statement.execute("CREATE TABLE user_phones(user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,phone TEXT UNIQUE NOT NULL,verified INTEGER NOT NULL)");
            statement.execute("CREATE TABLE unverified_contacts(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,channel TEXT NOT NULL,contact TEXT NOT NULL,PRIMARY KEY(user_id,channel))");
            statement.execute("CREATE TABLE user_blocks(user_id TEXT PRIMARY KEY REFERENCES users(id),actor_id TEXT NOT NULL REFERENCES users(id),reason TEXT NOT NULL,created INTEGER NOT NULL)");
            statement.execute("CREATE TABLE ai_points (user_id TEXT PRIMARY KEY REFERENCES users(id), balance INTEGER NOT NULL, updated INTEGER NOT NULL)");
        }
    }

    static LegacyFixture create(Path file) throws SQLException {
        return new LegacyFixture(file, true, null);
    }

    /** 旧 Node Hub 形态：users.email NOT NULL（没有只有手机号的用户）。 */
    static LegacyFixture nodeGeneration(Path file) throws SQLException {
        return new LegacyFixture(file, false, null);
    }

    static LegacyFixture withUnknownColumn(Path file) throws SQLException {
        return new LegacyFixture(file, true, "nickname");
    }

    /** 插入一个用户，返回其 ID；密码按旧平台规则计算 scrypt。 */
    String user(String email, String name, String password, boolean admin, String created) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO users VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, email);
            insert.setString(3, name);
            insert.setString(4, password == null ? "not-a-scrypt-hash" : hash(password));
            insert.setInt(5, admin ? 1 : 0);
            insert.setString(6, created);
            insert.executeUpdate();
        }
        try (PreparedStatement points = connection.prepareStatement("INSERT INTO ai_points VALUES (?, 12345, 0)")) {
            points.setString(1, id);
            points.executeUpdate();
        }
        return id;
    }

    void phone(String userId, String phone, long verified) throws SQLException {
        execute("INSERT INTO user_phones VALUES (?, ?, ?)", userId, phone, verified);
    }

    void unverified(String userId, String channel, String contact) throws SQLException {
        execute("INSERT INTO unverified_contacts VALUES (?, ?, ?)", userId, channel, contact);
    }

    void block(String userId, String actorId, String reason, long created) throws SQLException {
        execute("INSERT INTO user_blocks VALUES (?, ?, ?, ?)", userId, actorId, reason, created);
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    /** 旧平台 AccountCrypto.password：16 字节随机 salt 的十六进制文本按 UTF-8 参与 scrypt。 */
    static String hash(String password) {
        byte[] raw = new byte[16];
        new java.security.SecureRandom().nextBytes(raw);
        String salt = HexFormat.of().formatHex(raw);
        byte[] derived = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8), 16384, 8, 1, 64);
        return salt + ":" + HexFormat.of().formatHex(derived);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
