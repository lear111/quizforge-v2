package cloud.treenewbee.quizforge.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实锁等待建立时序，不靠线程启动先后或固定等待复现。 */
@Timeout(45)
class ImportQueueRecoveryIntegrationTest extends MySqlTestBase {
    static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    static final String OWNER = "00000000-0000-4000-8000-000000000001";
    @Autowired ImportQueueMapper rows;
    @Autowired ImportQueueJobs queue;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    final String pack = Ids.uuidV7();

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM content_import_job WHERE pack_id=?", pack);
    }

    @Test void formerRangeUpdateDeadlocksWithPrimaryKeyFencedWorker() throws Exception {
        String id = running(1);
        // 模拟历史任务占多数，确认旧恢复语句选择状态二级索引。
        List<Object[]> history = new ArrayList<>();
        for (int i = 2; i < 1002; i++) history.add(new Object[]{Ids.uuidV7(), pack, i, utc(NOW), utc(NOW), utc(NOW)});
        jdbc.batchUpdate("INSERT INTO content_import_job(id,pack_id,revision,status,available_at,created_at,updated_at) VALUES(?,?,?,'completed',?,?,?)", history);
        var plan = jdbc.queryForList("EXPLAIN UPDATE content_import_job SET status=IF(attempts<4,'queued','failed'),generation=generation+1 WHERE status='running' AND lease_until <= ? LIMIT 20", utc(NOW));
        assertThat(plan.getFirst().get("key")).isIn("idx_import_lease", "idx_import_ready");
        var locked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = executor.submit(() -> tx.execute(status -> {
                assertThat(rows.fence(id, OWNER, 1, NOW.minusSeconds(2))).isPresent();
                locked.countDown();
                waitFor(finish);
                return rows.finish(id, OWNER, 1, "completed", null, NOW, NOW.minusSeconds(2));
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var recovery = executor.submit(() -> tx.execute(status -> jdbc.update("""
                    UPDATE content_import_job SET status=IF(attempts<4,'queued','failed'),generation=generation+1,
                      failure_code='WORKER_RESTARTED',available_at=TIMESTAMPADD(SECOND,5*attempts*attempts,?),updated_at=?
                    WHERE status='running' AND lease_until <= ? LIMIT 20
                    """, utc(NOW), utc(NOW), utc(NOW))));
            try {
                var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
                await().atMost(Duration.ofSeconds(10)).until(() -> observer.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_NAME='content_import_job'
                        """, Integer.class) > 0);
            } finally {
                finish.countDown();
            }
            List<Throwable> errors = new ArrayList<>();
            for (var future : List.of(worker, recovery)) {
                try { future.get(10, TimeUnit.SECONDS); }
                catch (ExecutionException failure) { errors.add(failure.getCause()); }
            }
            assertThat(errors).singleElement().satisfies(error -> {
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                assertThat(cause).isInstanceOf(SQLException.class);
                assertThat(((SQLException) cause).getErrorCode()).isEqualTo(1213);
            });
        }
    }

    @Test void recoverySkipsWorkerLockedRowAndRecoversOtherExpiredRowsWithoutDeadlock() throws Exception {
        String busy = running(1), expired = running(2);
        var locked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = executor.submit(() -> tx.execute(status -> {
                assertThat(rows.fence(busy, OWNER, 1, NOW.minusSeconds(2))).isPresent();
                locked.countDown();
                waitFor(finish);
                return rows.finish(busy, OWNER, 1, "completed", null, NOW, NOW.minusSeconds(2));
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(executor.submit(() -> queue.recoverExpired(NOW)).get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(rows.find(busy).orElseThrow().status()).isEqualTo("running");
                var recovered = rows.find(expired).orElseThrow();
                assertThat(recovered.status()).isEqualTo("queued");
                assertThat(recovered.generation()).isEqualTo(2);
                assertThat(recovered.availableAt()).isEqualTo(NOW.plusSeconds(5));
                assertThat(recovered.leaseOwner()).isNull();
                assertThat(recovered.leaseUntil()).isNull();
            } finally {
                finish.countDown();
            }
            assertThat(worker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(queue.recoverExpired(NOW)).isZero();
        }
    }

    @Test void concurrentRecoverersOnlyAdvanceOneGenerationAndRenewedLeaseIsRechecked() throws Exception {
        String id = running(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); return queue.recoverExpired(NOW); });
            var second = executor.submit(() -> { gate.await(); return queue.recoverExpired(NOW); });
            gate.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(rows.find(id).orElseThrow().generation()).isEqualTo(2);
        jdbc.update("UPDATE content_import_job SET status='running',lease_until=?,attempts=4 WHERE id=?", utc(NOW.plusSeconds(30)), id);
        assertThat(rows.lockExpired(id, NOW)).isEmpty();
        assertThat(queue.recoverExpired(NOW)).isZero();
        assertThat(queue.recoverExpired(NOW.plusSeconds(31))).isEqualTo(1);
        assertThat(rows.find(id).orElseThrow().status()).isEqualTo("failed");
    }

    private String running(int revision) {
        String id = Ids.uuidV7();
        jdbc.update("""
                INSERT INTO content_import_job(id,pack_id,revision,status,attempts,available_at,created_at,updated_at,
                    lease_owner,lease_until,generation) VALUES(?,?,?,'running',1,?,?,?,?,?,1)
                """, id, pack, revision, utc(NOW), utc(NOW), utc(NOW), OWNER, utc(NOW.minusSeconds(1)));
        return id;
    }

    private static java.time.LocalDateTime utc(Instant instant) {
        return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private static void waitFor(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
