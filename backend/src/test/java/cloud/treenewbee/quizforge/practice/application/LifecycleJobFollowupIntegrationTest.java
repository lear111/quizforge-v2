package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.json.JsonMapper;

@Timeout(30)
class LifecycleJobFollowupIntegrationTest extends MySqlTestBase {
    static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired LifecycleJobMapper mapper;
    @Autowired PracticeContextMapper contexts;
    @Autowired PracticeLifecycleMapper lifecycle;
    @Autowired PracticeRoundMapper rounds;
    @Autowired PracticeHistoryMapper histories;
    @Autowired ReceiptMapper receipts;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    final PracticeReadService reads = mock(PracticeReadService.class);
    final PracticeSummaryService summaries = mock(PracticeSummaryService.class);
    final RuleRunnerClient runner = mock(RuleRunnerClient.class);
    final LifecycleJobPoller poller = mock(LifecycleJobPoller.class);
    String user;
    String collection;
    String context;
    String round;
    String bank;
    PracticeLifecycleJobs service;

    @BeforeEach
    void fixture() {
        user = Ids.uuidV7();
        collection = Ids.uuidV7();
        context = Ids.uuidV7();
        round = Ids.uuidV7();
        bank = Ids.uuidV7();
        jdbc.update("""
                INSERT INTO practice_context(id,user_id,collection_id,kind,scope_key,current_round_id,created_at,updated_at)
                VALUES(?,?,?,'practice','default',?,?,?)
                """, context, user, collection, round, time(NOW), time(NOW));
        rounds.insertRound(new PracticeRoundRow(round, user, collection, context, "practice", "in_progress",
                "{}", null, NOW, NOW, null, null));
        when(reads.resolveCollection(user, collection)).thenReturn(new PracticeReadService.CollectionTarget(context, bank));
        when(reads.collectionSnapshot(user, collection)).thenReturn("{\"title\":\"游标题库\",\"questions\":[]}");
        when(summaries.current(eq(user), eq(bank), any())).thenAnswer(call -> JSON.createObjectNode()
                .put("summaryVersion", "v1").put("questionCount", 3).put("answeredUnsubmittedCount", 0));
        when(runner.background(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        service = create(mapper);
    }

    private PracticeLifecycleJobs create(LifecycleJobMapper jobs) {
        var result = new PracticeLifecycleJobs(jobs, contexts, lifecycle, rounds, null, reads, summaries, histories,
                receipts, null, runner, tx, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
        result.poller = poller;
        return result;
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("practice_lifecycle_job", "practice_request_receipt", "practice_round_question",
                "practice_round", "practice_context")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", user);
        }
        jdbc.update("DELETE FROM content_question WHERE bank_id = ?", bank);
    }

    @ParameterizedTest
    @ValueSource(strings = {"finish", "archive"})
    void newJobWakesOnlyAfterCommitAndReplayDoesNotScheduleAnotherWake(String kind) {
        allow(kind);
        tx.executeWithoutResult(status -> {
            service.begin(user, collection, "new-job", round, "v1", kind);
            verify(poller, never()).wake();
        });
        verify(poller).wake();
        clearInvocations(poller);
        service.begin(user, collection, "new-job", round, "v1", kind);
        verify(poller, never()).wake();
    }

    @ParameterizedTest
    @ValueSource(strings = {"finish", "archive"})
    void rolledBackCreationDoesNotWakeAndLeavesNoJobOrReceipt(String kind) {
        allow(kind);
        tx.executeWithoutResult(status -> {
            service.begin(user, collection, "rolled-back", round, "v1", kind);
            status.setRollbackOnly();
        });
        verify(poller, never()).wake();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_lifecycle_job WHERE user_id = ?", Integer.class, user)).isZero();
        assertThat(receipts.find(user, "rolled-back")).isNull();
    }

    @Test
    void cleanupDeletesOnlyStrictlyExpiredTerminalJobsAndRespectsBatchSize() {
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        var ended = new ArrayList<String>();
        for (String status : List.of("completed", "failed")) {
            for (int i = 0; i < 3; i++) {
                ended.add(insertJob(status, cutoff.minusMillis(i + 1)));
            }
            insertJob(status, cutoff);
            insertJob(status, NOW);
        }
        String queued = insertJob("queued", cutoff.minusSeconds(1));
        String running = insertJob("running", cutoff.minusSeconds(1));
        assertThat(mapper.deleteExpired("completed", cutoff, 2)).isEqualTo(2);
        assertThat(mapper.deleteExpired("failed", cutoff, 2)).isEqualTo(2);
        assertThat(mapper.deleteExpired("queued", cutoff, 100)).isZero();
        assertThat(mapper.deleteExpired("running", cutoff, 100)).isZero();
        assertThat(mapper.find(queued, user, collection)).isPresent();
        assertThat(mapper.find(running, user, collection)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_lifecycle_job WHERE user_id = ?", Integer.class, user))
                .isEqualTo(8);
        assertThat(mapper.deleteExpired("completed", cutoff, 2)).isEqualTo(1);
        assertThat(mapper.deleteExpired("failed", cutoff, 2)).isEqualTo(1);
        for (String id : ended) {
            assertThat(mapper.find(id, user, collection)).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_lifecycle_job WHERE user_id = ?", Integer.class, user))
                .isEqualTo(6);
    }

    @Test
    void nextIgnoresEndedAndUnexpiredLeasesAndRequeuesAfterYield() {
        insertJob("completed", NOW.minusSeconds(10));
        insertJob("failed", NOW.minusSeconds(10));
        String expired = insertJob("running", NOW.minusSeconds(5));
        String active = insertJob("running", NOW.minusSeconds(10));
        jdbc.update("UPDATE practice_lifecycle_job SET lease_until = ? WHERE id = ?", time(NOW), active);
        String queued = insertJob("queued", NOW.minusSeconds(1));
        assertThat(tx.<String>execute(status -> mapper.next(NOW).orElseThrow().id())).isEqualTo(expired);
        mapper.claim(expired, "new-owner", NOW.plusSeconds(120), NOW);
        assertThat(tx.<String>execute(status -> mapper.next(NOW).orElseThrow().id())).isEqualTo(queued);
        mapper.claim(queued, "new-owner", NOW.plusSeconds(120), NOW);
        assertThat(tx.<java.util.Optional<LifecycleJobMapper.Job>>execute(status -> mapper.next(NOW))).isEmpty();
        mapper.yield(expired, "new-owner", NOW);
        assertThat(tx.<String>execute(status -> mapper.next(NOW).orElseThrow().id())).isEqualTo(expired);
    }

    @Test
    void nextSkipsRowLockedByAnotherWorker() throws Exception {
        String first = insertJob("queued", NOW.minusSeconds(2));
        String second = insertJob("queued", NOW.minusSeconds(1));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holding = pool.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(mapper.next(NOW).orElseThrow().id()).isEqualTo(first);
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(tx.<String>execute(status -> mapper.next(NOW).orElseThrow().id())).isEqualTo(second);
            } finally {
                release.countDown();
            }
            holding.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void archiveUsesPositionKeysetWithGapsAndResumesAfterLeaseRecovery() {
        questions();
        allow("archive");
        String id = service.begin(user, collection, "archive", round, "v1", "archive").path("jobId").asString();
        service.tick();
        assertThat(mapper.find(id, user, collection).orElseThrow().archivePosition()).isEqualTo(-4);
        service.tick();
        assertThat(mapper.find(id, user, collection).orElseThrow().archivePosition()).isEqualTo(7);
        jdbc.update("UPDATE practice_lifecycle_job SET status = 'running', lease_owner = 'lost-worker', lease_until = ? WHERE id = ?",
                time(NOW.minusSeconds(1)), id);
        create(mapper).tick();
        var job = mapper.find(id, user, collection).orElseThrow();
        assertThat(job.archivePosition()).isEqualTo(10000);
        assertThat(job.completed()).isEqualTo(3);
        assertThat(mapper.nextQuestion(bank, 10000)).isEmpty();
        var order = org.mockito.Mockito.inOrder(reads);
        order.verify(reads).question(user, collection, "z-first", null);
        order.verify(reads).question(user, collection, "a-second", null);
        order.verify(reads).question(user, collection, "m-third", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE round_id = ?", Integer.class, round))
                .isEqualTo(3);
    }

    @Test
    void archiveCursorAndSnapshotRollbackTogetherAndOldOwnerCannotAdvanceIt() {
        questions();
        allow("archive");
        var broken = spy(mapper);
        doThrow(new IllegalStateException("模拟进度写入失败")).when(broken)
                .archiveProgress(anyString(), anyString(), anyInt(), anyInt(), any(), any());
        var failing = create(broken);
        String id = failing.begin(user, collection, "archive", round, "v1", "archive").path("jobId").asString();
        failing.tick();
        var job = mapper.find(id, user, collection).orElseThrow();
        assertThat(job.status()).isEqualTo("failed");
        assertThat(job.completed()).isZero();
        assertThat(job.archivePosition()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE round_id = ?", Integer.class, round))
                .isZero();
        assertThat(mapper.archiveProgress(id, "stale-owner", 1, -4, NOW.plusSeconds(120), NOW)).isZero();
    }

    private void questions() {
        var positions = List.of(-4, 7, 10000);
        var ids = List.of("z-first", "a-second", "m-third");
        for (int i = 0; i < ids.size(); i++) {
            String qid = ids.get(i);
            jdbc.update("""
                    INSERT INTO content_question(bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256)
                    VALUES(?,?,?,? ,UNHEX(REPEAT('11',32)),'{}',1,UNHEX(REPEAT('22',32)))
                    """, bank, qid, positions.get(i), qid);
            when(reads.question(user, collection, qid, null)).thenReturn(JSON.createObjectNode()
                    .set("state", JSON.createObjectNode().put("status", "unanswered")));
            when(reads.resolveTarget(user, collection, qid)).thenReturn(new PracticeReadService.SubmitTarget(context,
                    new byte[32], new byte[32]));
        }
    }

    private void allow(String kind) {
        if (kind.equals("archive")) {
            jdbc.update("UPDATE practice_context SET completion_status = 'completed' WHERE id = ?", context);
            jdbc.update("UPDATE practice_round SET status = 'completed' WHERE id = ?", round);
        }
    }

    private String insertJob(String status, Instant updated) {
        String id = Ids.uuidV7();
        mapper.insert(id, user, collection, context, round, "finish", 0, updated);
        jdbc.update("UPDATE practice_lifecycle_job SET status = ?, lease_until = ? WHERE id = ?",
                status, time(updated), id);
        return id;
    }

    private static Timestamp time(Instant value) {
        return Timestamp.from(value);
    }
}
