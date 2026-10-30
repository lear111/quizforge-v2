package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(DraftServiceTest.Configuration.class)
@Timeout(60)
class DraftServiceTest extends MySqlTestBase {
    private static final Instant NOW = Instant.parse("2026-10-29T12:34:56.789Z");
    private static final LocalDateTime CREATED = LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DraftService drafts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired DraftSqlGate gate;
    private final List<Fixture> fixtures = new ArrayList<>();

    record Fixture(String user, String context, String collection, String bank, byte[] digest, byte[] content) {}
    record State(String user, String status, long revision, String answer, byte[] content, Instant updatedAt) {}
    record Attempt(String answer, Long revision, RuntimeException error) {}

    @BeforeEach
    void resetClockAndGate() { clock.now.set(NOW); gate.clear(); }

    @AfterEach
    void removeOnlyThisTestsCommittedRows() {
        gate.clear();
        List<Long> progressVersions = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            progressVersions.add(jdbc.queryForObject(
                    "SELECT progress_version FROM practice_context WHERE id=?", Long.class, fixture.context()));
            jdbc.update("DELETE FROM practice_question_state WHERE context_id=?", fixture.context());
            jdbc.update("DELETE FROM practice_context WHERE id=?", fixture.context());
            jdbc.update("DELETE FROM practice_collection WHERE id=?", fixture.collection());
            jdbc.update("DELETE FROM content_question WHERE bank_id=?", fixture.bank());
            jdbc.update("DELETE FROM content_bank WHERE id=?", fixture.bank());
            jdbc.update("DELETE FROM content_extension_package WHERE digest=?", fixture.digest());
            jdbc.update("DELETE FROM account_user WHERE id=?", fixture.user());
        }
        // Check after cleanup so a regression does not leave committed fixtures behind.
        assertThat(progressVersions).as("draft saves preserve progress_version").containsOnly(17L);
    }

    @Test
    void firstSaveInsertsDraftWithRevisionOneAnswerHashAndUtcTimestamp() {
        Fixture fixture = fixture();
        assertThat(save(fixture, "q1", 0, "{\"text\":\"第一次😀\",\"parts\":[1,true]}")).isEqualTo(1);
        assertState(fixture, "q1", 1, "{\"text\":\"第一次😀\",\"parts\":[1,true]}", NOW);
        assertThat(count(fixture)).isEqualTo(1);
    }

    @Test
    void consecutiveSavesAdvanceOneTwoThreeAndReplaceAnswerEachTime() {
        Fixture fixture = fixture();
        for (long revision = 0; revision < 3; revision++) {
            Instant time = NOW.plusSeconds(revision); clock.now.set(time);
            String answer = "{\"edit\":" + revision + "}";
            assertThat(save(fixture, "q1", revision, answer)).isEqualTo(revision + 1);
            assertState(fixture, "q1", revision + 1, answer, time);
        }
        assertThat(count(fixture)).isEqualTo(1);
    }

    @Test
    void staleRevisionReturns409AndLeavesEntireRowUnchanged() {
        Fixture fixture = fixture();
        save(fixture, "q1", 0, "{\"edit\":1}");
        save(fixture, "q1", 1, "{\"edit\":2}");
        var before = wholeRow(fixture, "q1"); clock.now.set(NOW.plusSeconds(10));
        assertConflict(() -> save(fixture, "q1", 1, "{\"edit\":\"stale\"}"));
        assertThat(wholeRow(fixture, "q1")).usingRecursiveComparison().isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 8})
    void submittedAnswerCannotBeOverwrittenEvenWithCurrentRevision(long revision) {
        Fixture fixture = fixture();
        jdbc.update("""
                INSERT INTO practice_question_state
                (context_id,question_id,user_id,status,answer,result,grading_status,score,max_score,revision,content_sha256,updated_at)
                VALUES (?, 'q1', ?, 'submitted', '{"answer":"final"}', '{"score":1}', 'graded', 1, 2, 8, ?, ?)
                """, fixture.context(), fixture.user(), fixture.content(), CREATED);
        var before = wholeRow(fixture, "q1");
        assertConflict(() -> save(fixture, "q1", revision, "{\"answer\":\"overwrite\"}"));
        assertThat(wholeRow(fixture, "q1")).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void simultaneousFirstSavesHaveOneSuccessAndOne409WithoutDeadlock() throws Exception {
        // Both first saves reach their INSERT together. Previously UPDATE-then-INSERT deadlocked here (gap locks).
        Fixture fixture = fixture();
        var scenario = gate.arm(DraftSqlGate.Mode.BEFORE_WRITE, fixture.context());
        var attempts = race(fixture, "q1", fixture, "q1", 0, scenario);
        assertOneWinner(fixture, "q1", 1, attempts);
    }

    @Test
    void simultaneousUpdatesOfSameRevisionHaveOneSuccessAndOne409() throws Exception {
        Fixture fixture = fixture(); save(fixture, "q1", 0, "{}");
        var scenario = gate.arm(DraftSqlGate.Mode.BEFORE_WRITE, fixture.context());
        var attempts = race(fixture, "q1", fixture, "q1", 1, scenario);
        assertOneWinner(fixture, "q1", 2, attempts);
    }

    @Test
    void firstSaveDuplicateAfterWinnerCommitsIs409WithTwoLiveTransactions() throws Exception {
        Fixture fixture = fixture();
        var scenario = gate.arm(DraftSqlGate.Mode.AFTER_WINNER_COMMIT, fixture.context());
        var attempts = race(fixture, "q1", fixture, "q1", 0, scenario);
        assertOneWinner(fixture, "q1", 1, attempts);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void otherUserCannotOverwriteAnExistingOwnersRow(long revision) {
        Fixture owner = fixture(), other = fixture();
        save(owner, "q1", 0, "{\"owner\":true}"); var before = wholeRow(owner, "q1");
        assertConflict(() -> drafts.saveDraft(other.user(), owner.context(), "q1", revision, "{\"intruder\":true}", owner.content()));
        assertThat(wholeRow(owner, "q1")).usingRecursiveComparison().isEqualTo(before);
        assertThat(count(other)).isZero();
    }

    @Test
    void otherUserCannotInsertFirstDraftIntoOwnersEmptyContext() {
        Fixture owner = fixture(), other = fixture();
        assertConflict(() -> drafts.saveDraft(other.user(), owner.context(), "q1", 0, "{\"intruder\":true}", owner.content()));
        assertThat(count(owner)).isZero();
        assertThat(save(owner, "q1", 0, "{\"owner\":true}")).isEqualTo(1);
    }

    @Test
    void differentQuestionsAndDifferentUsersKeepIndependentRevisionsAndAnswers() {
        Fixture first = fixture(), second = fixture(); addQuestion(first, "q2", 1);
        save(first, "q1", 0, "{\"slot\":\"A1\"}");
        save(first, "q2", 0, "{\"slot\":\"A2\"}");
        save(second, "q1", 0, "{\"slot\":\"B1\"}");
        clock.now.set(NOW.plusSeconds(1)); save(first, "q1", 1, "{\"slot\":\"A1-new\"}");
        assertState(first, "q1", 2, "{\"slot\":\"A1-new\"}", NOW.plusSeconds(1));
        assertState(first, "q2", 1, "{\"slot\":\"A2\"}", NOW);
        assertState(second, "q1", 1, "{\"slot\":\"B1\"}", NOW);
        assertThat(count(first)).isEqualTo(2); assertThat(count(second)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentExistingRowsCanBothUpdateBeforeEitherTransactionCommits(boolean differentUser) throws Exception {
        Fixture first = fixture(), second = differentUser ? fixture() : first;
        String secondQuestion = differentUser ? "q1" : "q2";
        if (!differentUser) addQuestion(first, secondQuestion, 1);
        save(first, "q1", 0, "{}"); save(second, secondQuestion, 0, "{}");
        var scenario = gate.arm(DraftSqlGate.Mode.AFTER_UPDATE,
                differentUser ? new String[] {first.context(), second.context()} : new String[] {first.context()});
        var attempts = race(first, "q1", second, secondQuestion, 1, scenario);
        assertThat(attempts).allSatisfy(attempt -> { assertThat(attempt.error()).isNull(); assertThat(attempt.revision()).isEqualTo(2); });
        assertState(first, "q1", 2, attempts.get(0).answer(), NOW);
        assertState(second, secondQuestion, 2, attempts.get(1).answer(), NOW);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void invalidJsonOnInsertOrUpdateRemainsDatabaseFailureAndRollsBack(long revision) {
        Fixture fixture = fixture();
        if (revision != 0) save(fixture, "q1", 0, "{\"valid\":true}");
        var before = revision == 0 ? null : wholeRow(fixture, "q1");
        assertThatThrownBy(() -> save(fixture, "q1", revision, "{not-json"))
                .isInstanceOf(DataIntegrityViolationException.class).isNotInstanceOf(ApiException.class)
                .satisfies(error -> {
                    Throwable root = error; while (root.getCause() != null) root = root.getCause();
                    assertThat(root).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) root).getErrorCode()).isEqualTo(3140);
                });
        if (before == null) assertThat(count(fixture)).isZero();
        else assertThat(wholeRow(fixture, "q1")).usingRecursiveComparison().isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 9})
    void missingRowWithNonzeroRevisionReturns409WithoutInsert(long revision) {
        Fixture fixture = fixture();
        assertConflict(() -> save(fixture, "q1", revision, "{}"));
        assertThat(count(fixture)).isZero();
    }

    private List<Attempt> race(Fixture first, String firstQuestion, Fixture second, String secondQuestion,
            long revision, DraftSqlGate.Scenario scenario) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = workers.submit(() -> {
                try { return attempt(first, firstQuestion, revision, "{\"writer\":\"winner\"}"); }
                finally { scenario.committed.countDown(); } // saveDraft returns only after the proxy commits.
            });
            var two = workers.submit(() -> attempt(second, secondQuestion, revision, "{\"writer\":\"loser\"}"));
            var results = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            scenario.assertSeparateTransactions(); return results;
        }
    }

    private Attempt attempt(Fixture fixture, String question, long revision, String answer) {
        try { return new Attempt(answer, save(fixture, question, revision, answer), null); }
        catch (RuntimeException error) { return new Attempt(answer, null, error); }
    }

    private void assertOneWinner(Fixture fixture, String question, long expectedRevision, List<Attempt> attempts) {
        assertThat(attempts.stream().filter(a -> a.error() == null).toList()).as("outcomes: %s", attempts).hasSize(1);
        Attempt winner = attempts.stream().filter(a -> a.error() == null).findFirst().orElseThrow();
        Attempt loser = attempts.stream().filter(a -> a.error() != null).findFirst().orElseThrow();
        assertThat(winner.revision()).isEqualTo(expectedRevision);
        assertState(fixture, question, expectedRevision, winner.answer(), NOW);
        assertThat(count(fixture)).isEqualTo(1);
        assertThat(loser.error()).as("outcomes: %s", attempts).isInstanceOf(ApiException.class);
        ApiException error = (ApiException) loser.error();
        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT); assertThat(error.code()).isEqualTo("REVISION_CONFLICT");
    }

    private long save(Fixture fixture, String question, long revision, String answer) {
        return drafts.saveDraft(fixture.user(), fixture.context(), question, revision, answer, fixture.content());
    }

    private void assertConflict(Runnable save) {
        assertThatThrownBy(save::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.code()).isEqualTo("REVISION_CONFLICT");
        });
    }

    private void assertState(Fixture fixture, String question, long revision, String answer, Instant time) {
        State row = jdbc.queryForObject("SELECT * FROM practice_question_state WHERE context_id=? AND question_id=?", (rs, index) ->
                new State(rs.getString("user_id"), rs.getString("status"), rs.getLong("revision"), rs.getString("answer"),
                        rs.getBytes("content_sha256"), rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), fixture.context(), question);
        assertThat(row.user()).isEqualTo(fixture.user()); assertThat(row.status()).isEqualTo("draft");
        assertThat(row.revision()).isEqualTo(revision); assertThat(row.content()).containsExactly(fixture.content());
        assertThat(row.updatedAt()).isEqualTo(time); assertThat(JSON.readTree(row.answer())).isEqualTo(JSON.readTree(answer));
    }

    private java.util.Map<String, Object> wholeRow(Fixture fixture, String question) {
        return jdbc.queryForMap("SELECT * FROM practice_question_state WHERE context_id=? AND question_id=?", fixture.context(), question);
    }

    private int count(Fixture fixture) { return jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE context_id=?", Integer.class, fixture.context()); }

    private Fixture fixture() {
        String user = Ids.uuidV7(), context = Ids.uuidV7(), collection = Ids.uuidV7(), bank = Ids.uuidV7();
        byte[] digest = hash("extension-" + bank), content = hash("question-" + bank);
        Fixture fixture = new Fixture(user, context, collection, bank, digest, content); fixtures.add(fixture);
        jdbc.update("INSERT INTO account_user (id,email,name,password_hash,password_algo,created_at,updated_at) VALUES (?, ?, '草稿测试', 'unused', 'scrypt-v1', ?, ?)", user, user + "@example.test", CREATED, CREATED);
        jdbc.update("INSERT INTO content_extension_package (digest,extension_id,version,name,manifest,api_major,api_min_minor,created_at) VALUES (?, ?, '1.0.0', '测试拓展', '{}', 1, 0, ?)", digest, "test-" + bank, CREATED);
        jdbc.update("INSERT INTO content_bank (id,content_sha256,source_title,format_version,features,question_count,created_at) VALUES (?, ?, '测试题库', 2, '{}', 1, ?)", bank, hash("bank-" + bank), CREATED);
        addQuestion(fixture, "q1", 0);
        jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,created_at) VALUES (?, ?, ?, '草稿题库', '{\"type\":\"test\"}', ?)", collection, user, bank, CREATED);
        jdbc.update("INSERT INTO practice_context (id,user_id,collection_id,kind,scope_key,progress_version,created_at,updated_at) VALUES (?, ?, ?, 'practice', 'test', 17, ?, ?)", context, user, collection, CREATED, CREATED);
        return fixture;
    }

    private void addQuestion(Fixture fixture, String question, int position) {
        jdbc.update("INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256) VALUES (?, ?, ?, '测试题目', ?, '{}', 1, ?)",
                fixture.bank(), question, position, fixture.digest(), fixture.content());
    }

    private static byte[] hash(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary MutableClock draftClock() { return new MutableClock(); }
        @Bean DraftSqlGate draftSqlGate() { return new DraftSqlGate(); }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
