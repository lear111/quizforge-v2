package cloud.treenewbee.quizforge.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为每个迁移来的老用户发放 1 元默认 AI 额度（需求 FR-09 v1.12，设计 08 第 1.1 节）。
 *
 * <p>与 V2 注册、V16 为已有用户补发使用同一条规则：流水 kind = default_credit、ref = 用户 ID，
 * 唯一约束 (kind, ref) 保证每个用户只发一次，无论经由注册、补发还是迁移。{@code migrate} 在迁移事务内
 * 为全部迁入用户开户并发放；{@code grant-default-credit} 可重复执行，只为还没有这条流水的用户补发。
 *
 * <p>与后端 AiBalanceService 的唯一变动入口语义一致：锁余额行 → 余额加额度、seq + 1 → 插入流水
 * （seq、balance_after 与余额行一致），对账检查同样适用于迁移写入的数据。
 */
public final class DefaultCredit {

    /** 1 元 = 10000 单位（0.0001 元为一个单位，设计 08 第 0 节）。 */
    public static final long AMOUNT_UNITS = 10_000;
    static final String KIND = "default_credit";

    /** 发放接口：在调用方的事务内为这些用户各发放 amountUnits；已发放过的用户跳过，返回本次新发放的人数。 */
    public interface Granter {
        int grant(Connection connection, List<String> userIds, long amountUnits, Instant at) throws SQLException;
    }

    /** 写 ai_account / ai_ledger 的实现。 */
    public static final Granter LEDGER = (connection, userIds, amountUnits, at) -> {
        Timestamp now = Timestamp.valueOf(at.atOffset(ZoneOffset.UTC).toLocalDateTime());
        int granted = 0;
        try (PreparedStatement open = connection.prepareStatement("""
                INSERT INTO ai_account (user_id, balance, ledger_seq, updated_at) VALUES (?, 0, 0, ?)
                ON DUPLICATE KEY UPDATE user_id = user_id""");
             PreparedStatement lock = connection.prepareStatement(
                     "SELECT balance, ledger_seq FROM ai_account WHERE user_id = ? FOR UPDATE");
             PreparedStatement exists = connection.prepareStatement(
                     "SELECT 1 FROM ai_ledger WHERE kind = '" + KIND + "' AND ref = ?");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE ai_account SET balance = ?, ledger_seq = ?, updated_at = ? WHERE user_id = ? AND ledger_seq = ?");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO ai_ledger (id, user_id, seq, delta, balance_after, kind, ref, created_at)
                     VALUES (UUID(), ?, ?, ?, ?, '""" + KIND + "', ?, ?)")) {
            for (String userId : userIds) {
                open.setString(1, userId);
                open.setTimestamp(2, now);
                open.executeUpdate();
                // 行已存在（刚插入或原有），加锁读不会落到间隙上。
                lock.setString(1, userId);
                long balance;
                long seq;
                try (ResultSet row = lock.executeQuery()) {
                    row.next();
                    balance = row.getLong(1);
                    seq = row.getLong(2);
                }
                exists.setString(1, userId);
                try (ResultSet row = exists.executeQuery()) {
                    if (row.next()) continue;
                }
                long after = Math.addExact(balance, amountUnits);
                update.setLong(1, after);
                update.setLong(2, seq + 1);
                update.setTimestamp(3, now);
                update.setString(4, userId);
                update.setLong(5, seq);
                if (update.executeUpdate() != 1) throw new SQLException("ai_account changed concurrently: " + userId);
                insert.setString(1, userId);
                insert.setLong(2, seq + 1);
                insert.setLong(3, amountUnits);
                insert.setLong(4, after);
                insert.setString(5, userId);
                insert.setTimestamp(6, now);
                insert.executeUpdate();
                granted++;
            }
        }
        return granted;
    };

    private DefaultCredit() {
    }

    /** 核对：每个用户恰好一条 default_credit 流水，金额为 amountUnits。返回不符合的用户。 */
    static List<Finding> verify(Connection connection, List<String> userIds, long amountUnits) throws SQLException {
        Map<String, long[]> found = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT ref, COUNT(*), MIN(delta) FROM ai_ledger WHERE kind = '" + KIND + "' GROUP BY ref")) {
            while (rows.next()) found.put(rows.getString(1), new long[] {rows.getLong(2), rows.getLong(3)});
        }
        List<Finding> findings = new ArrayList<>();
        for (String userId : userIds) {
            long[] entry = found.get(userId);
            if (entry == null || entry[0] != 1 || entry[1] != amountUnits) {
                findings.add(Finding.error("AI_CREDIT_MISMATCH", userId,
                        entry == null ? "缺少默认额度流水" : "默认额度流水 " + entry[0] + " 条、金额 " + entry[1]));
            }
        }
        return findings;
    }

    /** 报告中“默认额度”一节。 */
    static Map<String, Object> reportSection(String status, int users, Integer granted) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("status", status);
        section.put("amountUnits", AMOUNT_UNITS);
        section.put("amount", "1 元");
        section.put("kind", KIND);
        section.put("users", users);
        if (granted != null) section.put("granted", granted);
        return section;
    }
}
