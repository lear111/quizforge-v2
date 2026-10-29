package cloud.treenewbee.quizforge.practice.application;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only gates around real SQL; no result, transaction isolation or production code is replaced. */
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
final class DraftSqlGate implements Interceptor {
    enum Mode { BEFORE_UPDATE, AFTER_MISS, AFTER_UPDATE, AFTER_WINNER_COMMIT }

    static final class Scenario {
        final Mode mode;
        final Set<String> contexts;
        final Set<Long> connections = ConcurrentHashMap.newKeySet();
        final CyclicBarrier both = new CyclicBarrier(2);
        final CountDownLatch committed = new CountDownLatch(1);

        Scenario(Mode mode, String... contexts) { this.mode = mode; this.contexts = Set.of(contexts); }

        void meet() throws Exception { both.await(10, TimeUnit.SECONDS); }

        void assertSeparateTransactions() {
            org.assertj.core.api.Assertions.assertThat(connections).as("two live MySQL transactions").hasSize(2);
        }
    }

    private volatile Scenario scenario;

    Scenario arm(Mode mode, String... contexts) { return scenario = new Scenario(mode, contexts); }
    void clear() { Scenario previous = scenario; scenario = null; if (previous != null) previous.committed.countDown(); }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Scenario current = scenario;
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        if (current == null || !statement.getId().equals("cloud.treenewbee.quizforge.practice.infrastructure.QuestionStateMapper.updateDraft")) {
            return invocation.proceed();
        }
        Map<?, ?> parameters = (Map<?, ?>) invocation.getArgs()[1];
        if (!current.contexts.contains(parameters.get("contextId"))) return invocation.proceed();
        Connection connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || connection.getAutoCommit()) {
            throw new AssertionError("DraftService must run inside its real Spring transaction");
        }
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new AssertionError("These tests must retain MySQL's production REPEATABLE READ isolation");
        }
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT CONNECTION_ID()")) {
            rows.next(); current.connections.add(rows.getLong(1));
        }
        if (current.mode == Mode.BEFORE_UPDATE || current.mode == Mode.AFTER_WINNER_COMMIT) current.meet();
        if (current.mode == Mode.AFTER_WINNER_COMMIT && ((String) parameters.get("answer")).contains("\"loser\"")) {
            if (!current.committed.await(10, TimeUnit.SECONDS)) throw new AssertionError("winner did not commit");
        }
        Object result = invocation.proceed();
        if (current.mode == Mode.AFTER_MISS || current.mode == Mode.AFTER_UPDATE) {
            int expected = current.mode == Mode.AFTER_MISS ? 0 : 1;
            if (!Integer.valueOf(expected).equals(result)) throw new AssertionError("unexpected UPDATE row count: " + result);
            current.meet();
        }
        return result;
    }
}
