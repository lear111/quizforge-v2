package cloud.treenewbee.quizforge.practice.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.shared.id.Ids;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Timeout(30)
class SubmitInfrastructureMapperTest extends MySqlTestBase {
    static final Instant NOW = Instant.parse("2026-10-30T12:34:56.789Z"), LATER = NOW.plusMillis(123);
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired PracticeContextMapper contexts;
    @Autowired PracticeRoundMapper rounds;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    String context, user, collection, otherContext, otherUser, otherCollection;

    @BeforeEach void fixture() {
        context = Ids.uuidV7(); user = Ids.uuidV7(); collection = Ids.uuidV7();
        otherContext = Ids.uuidV7(); otherUser = Ids.uuidV7(); otherCollection = Ids.uuidV7();
        for (var row : List.of(List.of(context, user, collection), List.of(otherContext, otherUser, otherCollection))) {
            jdbc.update("""
                    INSERT INTO practice_context (id,user_id,collection_id,kind,scope_key,created_at,updated_at)
                    VALUES (?,?,?,'practice','default',?,?)
                    """, row.get(0), row.get(1), row.get(2), Timestamp.from(NOW), Timestamp.from(NOW));
        }
    }

    @AfterEach void cleanup() {
        for (String owner : List.of(user, otherUser)) {
            jdbc.update("DELETE FROM practice_round_question WHERE user_id=?", owner);
            jdbc.update("DELETE FROM practice_round WHERE user_id=?", owner);
            jdbc.update("DELETE FROM practice_context WHERE user_id=?", owner);
        }
    }

    @Test void lockReturnsCurrentRoundAndProgressOnlyToOwner() {
        String round = Ids.uuidV7(); rounds.insertRound(round(round, "in_progress"));
        contexts.setCurrentRound(context, user, round, LATER); contexts.bumpProgressVersion(context, user, LATER);
        tx.executeWithoutResult(status -> {
            assertThat(contexts.lockForUpdate(context, user)).contains(new PracticeContextRow(context, round, 1));
            assertThat(contexts.lockForUpdate(context, otherUser)).isEmpty();
            assertThat(contexts.lockForUpdate(Ids.uuidV7(), user)).isEmpty();
        });
        var other = tx.execute(status -> contexts.lockForUpdate(otherContext, otherUser));
        assertThat(other).contains(new PracticeContextRow(otherContext, null, 0));
    }

    @Test void forUpdateHoldsTheLockUntilTransactionEnds() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(contexts.lockForUpdate(context, user)).isPresent(); locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                // MySQL itself detects the blocked competing lock. No timing/sleep assertion.
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    int original = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    try { contexts.lockForUpdate(context, user); }
                    finally { jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + original); }
                })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("Lock wait timeout");
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
        tx.executeWithoutResult(status -> assertThat(contexts.lockForUpdate(context, user)).isPresent());
    }

    @ParameterizedTest @ValueSource(strings = {"in_progress", "completed", "interrupted"})
    void insertRoundPersistsEveryV1ColumnAndMillisecondInstant(String state) {
        String id = Ids.uuidV7();
        var row = new PracticeRoundRow(id, user, collection, context, "practice", state,
                "{\"title\":\"冻结题库😀\",\"questions\":[\"q-1\"]}", "{\"score\":1.25}", NOW, LATER, LATER, LATER);
        assertThat(rounds.insertRound(row)).isEqualTo(1);
        var saved = jdbc.queryForMap("SELECT * FROM practice_round WHERE id=?", id);
        assertThat(saved).hasSize(13).containsEntry("history_frozen_at", null)
                .containsEntry("id", id).containsEntry("user_id", user)
                .containsEntry("context_id", context).containsEntry("collection_id", collection)
                .containsEntry("mode", "practice").containsEntry("status", state);
        sameJson(saved.get("collection_snapshot"), row.collectionSnapshot()); sameJson(saved.get("summary"), row.summary());
        assertThat(time("practice_round", "created_at", "id", id)).isEqualTo(NOW);
        for (String column : List.of("updated_at", "completed_at", "deleted_at"))
            assertThat(time("practice_round", column, "id", id)).isEqualTo(LATER);
    }

    @Test void insertRoundPreservesNullableColumnsAndExamMode() {
        var row = new PracticeRoundRow(Ids.uuidV7(), user, collection, context, "exam", "in_progress", "{}", null, NOW, NOW, null, null);
        assertThat(rounds.insertRound(row)).isEqualTo(1);
        var saved = jdbc.queryForMap("SELECT * FROM practice_round WHERE id=?", row.id());
        assertThat(saved).containsEntry("mode", "exam").containsEntry("summary", null)
                .containsEntry("completed_at", null).containsEntry("deleted_at", null);
    }

    @Test void duplicateRoundIdFailsAndInvalidJsonIsNotIgnored() {
        var row = round(Ids.uuidV7(), "in_progress"); rounds.insertRound(row);
        assertThatThrownBy(() -> rounds.insertRound(row)).isInstanceOf(DuplicateKeyException.class);
        var invalid = new PracticeRoundRow(Ids.uuidV7(), user, collection, context, "practice", "in_progress", "not json", null, NOW, NOW, null, null);
        assertThatThrownBy(() -> rounds.insertRound(invalid)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?", Integer.class, user)).isEqualTo(1);
    }

    @Test void roundInsertCannotUseAnothersContextOrMismatchedCollection() {
        var id = Ids.uuidV7();
        assertThat(rounds.insertRound(new PracticeRoundRow(id, otherUser, collection, context, "practice", "in_progress", "{}", null, NOW, NOW, null, null))).isZero();
        assertThat(rounds.insertRound(new PracticeRoundRow(id, user, otherCollection, context, "practice", "in_progress", "{}", null, NOW, NOW, null, null))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE id=?", Integer.class, id)).isZero();
    }

    @Test void setCurrentRoundChecksContextOwnerAndRoundScopeAndCanClearIt() {
        String first = Ids.uuidV7(), second = Ids.uuidV7();
        rounds.insertRound(round(first, "in_progress"));
        rounds.insertRound(new PracticeRoundRow(second, otherUser, otherCollection, otherContext, "practice", "in_progress", "{}", null, NOW, NOW, null, null));
        assertThat(contexts.setCurrentRound(context, otherUser, first, LATER)).isZero();
        assertThat(contexts.setCurrentRound(context, user, second, LATER)).isZero();
        assertThat(contexts.setCurrentRound(context, user, Ids.uuidV7(), LATER)).isZero();
        assertThat(contexts.setCurrentRound(context, user, first, LATER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_round_id FROM practice_context WHERE id=?", String.class, context)).isEqualTo(first);
        assertThat(time("practice_context", "updated_at", "id", context)).isEqualTo(LATER);
        assertThat(contexts.setCurrentRound(context, user, null, LATER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_round_id FROM practice_context WHERE id=?", String.class, context)).isNull();
        assertOtherContextUnchanged();
    }

    @Test void bumpProgressVersionIsAtomicAndOwnerScoped() throws Exception {
        assertThat(contexts.bumpProgressVersion(context, otherUser, LATER)).isZero();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> increment = () -> tx.execute(status -> { ready.countDown(); await(start); return contexts.bumpProgressVersion(context, user, LATER); });
            var first = pool.submit(increment); var second = pool.submit(increment);
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); } finally { start.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1); assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(tx.execute(status -> contexts.lockForUpdate(context, user)).orElseThrow().progressVersion()).isEqualTo(2);
        assertThat(time("practice_context", "updated_at", "id", context)).isEqualTo(LATER);
        assertOtherContextUnchanged();
    }

    @Test void upsertFreezesPayloadDigestAndTimestampThenUpdatesOnlyThatQuestion() {
        String first = Ids.uuidV7(), second = Ids.uuidV7();
        rounds.insertRound(round(first, "in_progress")); rounds.insertRound(round(second, "in_progress"));
        var old = snapshot(first, "q-1", user, 1, NOW);
        rounds.upsertRoundQuestion(old); rounds.upsertRoundQuestion(snapshot(first, "q-2", user, 1, NOW));
        rounds.upsertRoundQuestion(snapshot(second, "q-1", user, 1, NOW));
        assertSnapshot(old);
        var updated = snapshot(first, "q-1", user, 2, LATER);
        rounds.upsertRoundQuestion(updated); assertSnapshot(updated);
        assertSnapshot(snapshot(first, "q-2", user, 1, NOW)); assertSnapshot(snapshot(second, "q-1", user, 1, NOW));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE user_id=?", Integer.class, user)).isEqualTo(3);
    }

    @Test void roundQuestionCannotBeInsertedOrOverwrittenByAnotherUser() {
        String id = Ids.uuidV7(); rounds.insertRound(round(id, "in_progress"));
        var original = snapshot(id, "q-1", user, 1, NOW); rounds.upsertRoundQuestion(original);
        assertThat(rounds.upsertRoundQuestion(snapshot(id, "q-1", otherUser, 2, LATER))).isZero();
        assertThat(rounds.upsertRoundQuestion(snapshot(id, "q-2", otherUser, 2, LATER))).isZero();
        assertThat(rounds.upsertRoundQuestion(snapshot(Ids.uuidV7(), "q-1", user, 2, LATER))).isZero();
        assertSnapshot(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE round_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void roundAndSnapshotWritesRollBackWithCallerTransaction() {
        String id = Ids.uuidV7();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            contexts.lockForUpdate(context, user); rounds.insertRound(round(id, "in_progress"));
            contexts.setCurrentRound(context, user, id, LATER); contexts.bumpProgressVersion(context, user, LATER);
            rounds.upsertRoundQuestion(new PracticeRoundQuestionRow(id, "q-1", user, "invalid JSON", new byte[32], LATER));
        })).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE round_id=?", Integer.class, id)).isZero();
        var stored = tx.execute(status -> contexts.lockForUpdate(context, user));
        assertThat(stored).contains(new PracticeContextRow(context, null, 0));
    }

    private PracticeRoundRow round(String id, String state) { return new PracticeRoundRow(id, user, collection, context, "practice", state, "{}", null, NOW, NOW, null, null); }
    private PracticeRoundQuestionRow snapshot(String round, String qid, String owner, int revision, Instant time) {
        byte[] digest = new byte[32]; digest[0] = (byte) revision;
        String payload = JSON.writeValueAsString(Map.of("question", Map.of("id", qid, "data", Map.of("projected", true)),
                "state", Map.of("status", "submitted", "answer", Map.of("selected", revision), "result", Map.of("score", revision), "revision", revision)));
        return new PracticeRoundQuestionRow(round, qid, owner, payload, digest, time);
    }
    private void assertSnapshot(PracticeRoundQuestionRow row) {
        var saved = jdbc.queryForMap("SELECT * FROM practice_round_question WHERE round_id=? AND question_id=?", row.roundId(), row.questionId());
        assertThat(saved).hasSize(6).containsEntry("user_id", row.userId()); sameJson(saved.get("payload"), row.payload());
        assertThat((byte[]) saved.get("extension_digest")).isEqualTo(row.extensionDigest());
        assertThat(jdbc.<Instant>queryForObject("SELECT updated_at FROM practice_round_question WHERE round_id=? AND question_id=?",
                (rs, n) -> rs.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC), row.roundId(), row.questionId())).isEqualTo(row.updatedAt());
    }
    private void assertOtherContextUnchanged() {
        var stored = tx.execute(status -> contexts.lockForUpdate(otherContext, otherUser));
        assertThat(stored).contains(new PracticeContextRow(otherContext, null, 0));
    }
    private Instant time(String table, String column, String key, String id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE " + key + "=?",
                (rs, n) -> rs.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC), id);
    }
    private static void sameJson(Object actual, String expected) { assertThat(JSON.readTree((String) actual)).isEqualTo(JSON.readTree(expected)); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("SQL synchronization timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
