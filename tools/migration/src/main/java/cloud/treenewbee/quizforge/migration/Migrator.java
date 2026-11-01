package cloud.treenewbee.quizforge.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 迁移（详细设计 09 第 5.3 节）：
 * <ul>
 *   <li>目标库账号相关表必须全部为空，否则拒绝（防止迁入已有用户的库或重复迁移）；</li>
 *   <li>全部写入在一个事务内；提交前在同一事务里逐用户核对，任何失败整体回滚；</li>
 *   <li>{@code dryRun} 完整执行并核对后回滚。</li>
 * </ul>
 */
public final class Migrator {

    /** 测试用：插入第 n 个账号后模拟失败，验证整体回滚。生产代码不设置。 */
    @FunctionalInterface
    interface FailureInjection {
        void afterInsert(int insertedCount) throws SQLException;
    }

    public record Result(int migrated, boolean committed, List<Finding> findings) {
    }

    private final TargetDatabase target;
    private FailureInjection failure = count -> { };

    public Migrator(TargetDatabase target) {
        this.target = target;
    }

    Migrator failAfter(int count) {
        this.failure = inserted -> {
            if (inserted == count) throw new SQLException("Injected failure after " + count + " accounts");
        };
        return this;
    }

    public Result migrate(AccountPlan plan, boolean dryRun) {
        if (plan.hasErrors()) {
            throw new MigrationException("PLAN_HAS_ERRORS", "inspect 报告中存在 ERROR，修复或经作者决定后再迁移");
        }
        Connection connection = target.connection();
        try {
            if (target.schemaVersion() < TargetDatabase.MIN_SCHEMA_VERSION) {
                throw new MigrationException("TARGET_SCHEMA_OUTDATED",
                        "目标库结构版本低于 V" + TargetDatabase.MIN_SCHEMA_VERSION + "，请先用当前版本的 V2 完成建表");
            }
            connection.setAutoCommit(false);
            try {
                requireEmpty();
                int inserted = 0;
                for (var account : plan.accounts()) {
                    target.insert(account);
                    failure.afterInsert(++inserted);
                }
                List<Finding> mismatches = Verifier.compare(plan, target.readAccounts(), target);
                if (!mismatches.isEmpty()) {
                    connection.rollback();
                    return new Result(0, false, mismatches);
                }
                if (dryRun) {
                    connection.rollback();
                    return new Result(inserted, false, List.of());
                }
                connection.commit();
                return new Result(inserted, true, List.of());
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof MigrationException known) throw known;
                throw new MigrationException("MIGRATION_FAILED", "迁移失败，已整体回滚：" + error.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new MigrationException("MIGRATION_FAILED", "迁移失败：" + error.getMessage());
        }
    }

    private void requireEmpty() throws SQLException {
        for (var count : target.rowCounts().entrySet()) {
            if (count.getValue() != 0) {
                throw new MigrationException("TARGET_NOT_EMPTY", "目标库 " + count.getKey() + " 已有 " + count.getValue()
                        + " 行；迁移只能写入新建的空库，需要重来时请删除并重建目标库");
            }
        }
    }
}
