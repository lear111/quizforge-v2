package cloud.treenewbee.quizforge.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可选步骤：为每个迁移来的老用户发放 1 元默认 AI 额度（需求 FR-09 v1.12）。
 *
 * <p>V2 的 AI 余额与流水表由 L5.1 创建，本任务不创建 AI 表，因此该步骤目前<strong>待 L5.1 完成后执行</strong>。
 * L5.1 需要提供下面的接口实现（在同一事务内为每个用户：锁余额行 → 余额加 {@link #AMOUNT_UNITS} →
 * 插入一条流水 kind = migration、ref = legacy:&lt;userId&gt;，唯一约束 (kind, ref) 保证重复执行不重复发放），
 * 然后在 MigrationCli 的 grant-default-credit 命令中接入。
 */
public final class DefaultCredit {

    /** 1 元 = 10000 单位（0.0001 元为一个单位，设计 08 第 0 节）。 */
    public static final long AMOUNT_UNITS = 10_000;
    static final String STATUS_PENDING = "pending-L5.1";

    /** L5.1 需要实现的发放接口。 */
    public interface Granter {
        /** 在调用方的事务内为这些用户各发放 amountUnits；已发放过的用户跳过（按流水唯一约束判断）。 */
        void grant(Connection connection, List<String> userIds, long amountUnits, Instant at) throws SQLException;
    }

    private DefaultCredit() {
    }

    /** 报告中“默认额度”一节：说明状态、金额、人数与所需接口。 */
    static Map<String, Object> reportSection(int users, boolean aiTablesPresent) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("status", STATUS_PENDING);
        section.put("amountUnits", AMOUNT_UNITS);
        section.put("amount", "1 元");
        section.put("users", users);
        section.put("aiTablesPresent", aiTablesPresent);
        section.put("note", "待 L5.1 完成后执行 grant-default-credit：需要 ai_account（每用户余额）与 ai_ledger"
                + "（kind = migration、ref = legacy:<userId>、唯一约束 (kind, ref)）及 DefaultCredit.Granter 的实现");
        return section;
    }

    static boolean aiTablesPresent(Connection connection) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "ai_ledger", null)) {
            if (!rows.next()) return false;
        }
        try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "ai_account", null)) {
            return rows.next();
        }
    }
}
