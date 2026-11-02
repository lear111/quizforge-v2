package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(120)
@Import(DraftActionIntegrationTest.Gates.class)
class DraftActionIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN = "https://draft.example.test", TOKEN = "draft-test-runner";
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
            .withEnv("QF_RULE_WORKER_LIMIT", "2").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN))
            .withStartupTimeout(Duration.ofMinutes(2));
    static { RUNNER.start(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.storage.blob-dir", BLOBS::toString);
        registry.add("qf.import.temp-dir", UPLOADS::toString);
        registry.add("qf.security.allowed-origin", () -> ORIGIN);
        registry.add("qf.rule-runner.base-url", () -> "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090));
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
        // 语义测试不把 CI 冷启动耗时当作规则超时；短时限另有真实超时用例与单元断言。
        registry.add("qf.practice.draft-validation-deadline", () -> "5s");
    }

    @Autowired AuthService auth;
    @Autowired ImportService imports;
    @Autowired JdbcTemplate jdbc;
    @Autowired DraftSqlGate gate;
    @MockitoSpyBean PracticeReadMapper readMapper;
    @MockitoSpyBean PracticeReadService reads;
    @MockitoSpyBean cloud.treenewbee.quizforge.rules.RuleRunnerClient runner;
    @Autowired cloud.treenewbee.quizforge.content.infrastructure.BlobStore blobs;
    final List<String> extraQuestions = new ArrayList<>();
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<Viewer> viewers = new ArrayList<>();
    Viewer owner;
    record Viewer(String user, String cookie, String collection, String bank, String context, String qid) {
        String path() { return "/api/collections/bank/" + collection + "/questions/" + qid; }
    }

    @BeforeEach void setup() throws Exception { gate.clear(); owner = viewer(); }
    @AfterEach void cleanup() {
        gate.clear();
        for (Viewer v : viewers) {
            jdbc.update("DELETE FROM practice_request_receipt WHERE user_id=?", v.user());
            jdbc.update("DELETE FROM practice_question_state WHERE context_id=?", v.context());
            for (String qid : extraQuestions) jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id=?", v.bank(), qid);
            jdbc.update("DELETE FROM practice_context WHERE id=?", v.context());
            jdbc.update("DELETE FROM practice_collection WHERE id=?", v.collection());
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", v.user());
            jdbc.update("DELETE FROM account_user WHERE id=?", v.user());
        }
    }
    @AfterAll static void stop() throws Exception {
        RUNNER.stop();
        try (var files = Files.walk(ROOT)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }

    @Test void sameRequestReplaysIdenticalResponseAndIncrementsRevisionOnlyOnce() throws Exception {
        var body = draft("same-request", 0, "first");
        var first = post(owner, body); var replay = post(owner, body);
        assertSaved(first, 1, "first"); assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body()); assertState(owner, 1, "first");
        assertThat(receipts(owner)).isEqualTo(1);
    }

    @Test void realValidationStoresPartProgressAndReplayDoesNotCallRulesAgain() throws Exception {
        Viewer v = partsViewer();
        useRules(v, true);
        clearInvocations(runner);
        var body = partDraft("parts", 0, Map.of("left", "答了", "right", ""));
        var response = post(v, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("state").path("outlineStates"))
                .isEqualTo(JSON.readTree("[{\"id\":\"p1\",\"status\":\"draft\"},{\"id\":\"p2\",\"status\":\"unanswered\"}]"));
        assertThat(json(get(v.path(), v.cookie())).path("state")).isEqualTo(json(response).path("state"));
        assertThat(post(v, body).body()).isEqualTo(response.body());
        verify(runner, times(1)).execute(eq("validateAnswer"), any(), anyMap(), eq(Duration.ofSeconds(5)));
        assertThat(response.body()).doesNotContain("correctOptionId", "standardAnswer", "outlineScore");
    }

    @Test void extensionWithoutValidateAnswerFallsBackToNonemptyAnswer() throws Exception {
        Viewer v = partsViewer();
        useRules(v, false);
        var empty = post(v, partDraft("empty", 0, Map.of()));
        assertThat(json(empty).path("state").path("outlineStates").get(0).path("status").asString()).isEqualTo("unanswered");
        var filled = post(v, partDraft("filled", 1, Map.of("text", "作答")));
        assertThat(json(filled).path("state").path("outlineStates").valueStream().map(s -> s.path("status").asString()))
                .containsExactly("draft", "draft");
    }

    @Test void realRuleDeadlineFailureKeepsDraftAndNextRequestStillWorks() throws Exception {
        Viewer v = partsViewer();
        useScript(v, "QF.defineType({project(){return {maxScore:1}},grade(){return {score:0,maxScore:1,correct:false,feedback:null}},validateAnswer(){while(true){}},getScore(){return {score:0,maxScore:1}}});");
        long start = System.nanoTime();
        var timed = post(v, partDraft("timeout", 0, Map.of("text", "先保存")));
        assertThat(timed.statusCode()).as(timed.body()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(json(timed).path("state").path("outlineStates").isNull()).isTrue();
        useRules(v, false);
        var next = post(v, partDraft("recovered", 1, Map.of("text", "仍可保存")));
        assertThat(next.statusCode()).as(next.body()).isEqualTo(200);
        assertThat(json(next).path("state").path("outlineStates").get(0).path("status").asString()).isEqualTo("draft");
    }

    @Test void validationFailureSavesAnswerAndReplaysUnknownProgressWithoutRetryingRules() throws Exception {
        Viewer v = partsViewer();
        doThrow(new cloud.treenewbee.quizforge.rules.RuleRunnerException("仅测试：不可用"))
                .when(runner).execute(eq("validateAnswer"), any(), anyMap(), any(Duration.class));
        var body = partDraft("unavailable", 0, Map.of("text", "保留此答案"));
        var response = post(v, body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("state").path("outlineStates").isNull()).isTrue();
        assertThat(json(get(v.path(), v.cookie())).path("state")).isEqualTo(json(response).path("state"));
        assertThat(post(v, body).body()).isEqualTo(response.body());
        verify(runner, times(1)).execute(eq("validateAnswer"), any(), anyMap(), any(Duration.class));
    }

    @Test void slowValidationOutsideTransactionCannotOverwriteNewerDraftProgress() throws Exception {
        Viewer v = partsViewer();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (seen.incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                return cloud.treenewbee.quizforge.rules.RuleResult.success(JSON.readTree("{\"valid\":true}"));
            }
            return cloud.treenewbee.quizforge.rules.RuleResult.rejected("RULE_TIMEOUT");
        }).when(runner).execute(eq("validateAnswer"), any(), anyMap(), any(Duration.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> post(v, partDraft("older", 0, Map.of("text", "旧"))));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            try {
                // 第一个请求停在规则调用时，新草稿已能提交事务并清除旧状态。
                var second = pool.submit(() -> post(v, partDraft("newer", 1, Map.of("text", "新")))).get(15, TimeUnit.SECONDS);
                assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
                assertThat(json(second).path("state").path("revision").asLong()).isEqualTo(2);
                assertThat(json(second).path("state").path("outlineStates").isNull()).isTrue();
            } finally { release.countDown(); }
            assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally { release.countDown(); }
        var current = json(get(v.path(), v.cookie())).path("state");
        assertThat(current.path("revision").asLong()).isEqualTo(2);
        assertThat(current.path("answer").path("text").asString()).isEqualTo("新");
        assertThat(current.path("outlineStates").isNull()).isTrue();
    }

    @Test void declaredAnswerStateUsesOnlyWhitelistedDraftStatusesAndIds() throws Exception {
        Viewer v = partsViewer();
        var q = reads.ruleQuestion(v.user(), v.collection(), v.qid());
        assertThat(q.rulePackage().answerStateDeclared()).isTrue();
        doReturn(cloud.treenewbee.quizforge.rules.RuleResult.success(JSON.readTree("{\"valid\":true}")))
                .when(runner).execute(eq("validateAnswer"), any(), anyMap(), any(Duration.class));
        var body = partDraft("reported", 0, Map.of("selectedOptionId", "a"));
        body.put("data", Map.of("answer", Map.of("selectedOptionId", "a"), "answerState",
                Map.of("status", "draft", "outlineStates", List.of(Map.of("id", "p1", "status", "draft")))));
        var response = post(v, body);
        assertThat(json(response).path("state").path("outlineStates").get(0).path("status").asString()).isEqualTo("draft");
        body = partDraft("bad-reported", 1, Map.of("selectedOptionId", "a"));
        body.put("data", Map.of("answer", Map.of("selectedOptionId", "a"), "answerState",
                Map.of("outlineStates", List.of(Map.of("id", "p1", "status", "correct")))));
        var rejectedState = post(v, body);
        assertThat(rejectedState.statusCode()).isEqualTo(200);
        assertThat(json(rejectedState).path("state").path("outlineStates").isNull()).isTrue();
    }

    private Viewer partsViewer() {
        String qid = "progress-" + Ids.uuidV7();
        jdbc.update("""
                INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,data,content_sha256,max_score,outline)
                SELECT bank_id,?,2,title,extension_digest,data,content_sha256,max_score,?
                FROM content_question WHERE bank_id=? AND question_id=?
                """, qid, "{\"level\":\"parts\",\"items\":[{\"id\":\"p1\",\"label\":\"1\"},{\"id\":\"p2\",\"label\":\"2\"}]}", owner.bank(), owner.qid());
        extraQuestions.add(qid);
        return new Viewer(owner.user(), owner.cookie(), owner.collection(), owner.bank(), owner.context(), qid);
    }

    private void useRules(Viewer v, boolean hooks) throws Exception {
        String script = "QF.defineType({project(){return {maxScore:1}},grade(){return {score:0,maxScore:1,correct:false,feedback:null}},"
                + (hooks ? "validateAnswer(){return true},getScore(data,state){return {score:0,maxScore:1,outlineStates:[{id:'p1',status:state.answer.left?'draft':'unanswered'},{id:'p2',status:state.answer.right?'draft':'unanswered'}]}}"
                         : "getScore(){return {score:0,maxScore:1}}") + "});";
        useScript(v, script);
    }

    private void useScript(Viewer v, String script) throws Exception {
        var ref = new cloud.treenewbee.quizforge.rules.RulePackage.FileRef(blobs.put(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var schema = new cloud.treenewbee.quizforge.rules.RulePackage.FileRef(blobs.put("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var spec = new cloud.treenewbee.quizforge.rules.RulePackage(ref.sha256(),
                new cloud.treenewbee.quizforge.rules.RulePackage.ApiVersion(1, 1), false, false, ref, schema, schema);
        var original = reads.ruleQuestion(v.user(), v.collection(), v.qid());
        doReturn(new PracticeReadService.RuleQuestion(original.data(), spec, original.outline()))
                .when(reads).ruleQuestion(v.user(), v.collection(), v.qid());
    }

    private Map<String,Object> partDraft(String id, long revision, Object answer) {
        return new LinkedHashMap<>(Map.of("requestId", id, "revision", revision, "action", "draft", "data", Map.of("answer", answer)));
    }

    @Test void savingDraftNeverLoadsCollectionQuestionsOrBuildsRoundSnapshot() throws Exception {
        clearInvocations(readMapper, reads);
        assertSaved(post(owner, draft("no-snapshot-first", 0, "first")), 1, "first");
        assertSaved(post(owner, draft("no-snapshot-second", 1, "second")), 2, "second");
        verify(readMapper, never()).questions(anyString(), anyString(), anyString());
        verify(readMapper, never()).collections(anyString());
        verify(reads, never()).collectionSnapshot(anyString(), anyString());
        assertState(owner, 2, "second");
    }

    @Test void replayAfterAnotherSaveReturnsOriginalAnswerAndRevision() throws Exception {
        var original = draft("old-request", 0, "old");
        var first = post(owner, original); assertSaved(first, 1, "old");
        assertSaved(post(owner, draft("new-request", 1, "new")), 2, "new");
        assertThat(post(owner, original).body()).isEqualTo(first.body());
        assertState(owner, 2, "new"); assertThat(receipts(owner)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(longs = {0, 1})
    void simultaneousSameRequestFirstAndLaterSavesBothReturnOneRevision(long revision) throws Exception {
        if (revision == 1) assertSaved(post(owner, draft("seed", 0, "seed")), 1, "seed");
        var scenario = gate.arm(DraftSqlGate.Mode.BEFORE_WRITE, owner.context());
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < 2; i++) responses.add(pool.submit(() -> {
                ready.countDown(); assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                return post(owner, draft("concurrent", revision, "shared"));
            }));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var first = responses.get(0).get(60, TimeUnit.SECONDS); var second = responses.get(1).get(60, TimeUnit.SECONDS);
            assertSaved(first, revision + 1, "shared"); assertSaved(second, revision + 1, "shared");
            assertThat(second.body()).isEqualTo(first.body());
        } finally { gate.clear(); }
        scenario.assertSeparateTransactions();
        assertState(owner, revision + 1, "shared");
        assertThat(receipts(owner)).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE context_id=?", Integer.class, owner.context())).isEqualTo(1);
    }

    @Test void reusedRequestCannotOverwriteTheFirstAnswer() throws Exception {
        assertSaved(post(owner, draft("reused", 0, "first")), 1, "first");
        var rejected = post(owner, draft("reused", 0, "second"));
        assertThat(rejected.statusCode()).isBetween(400, 499);
        assertThat(json(rejected).path("error").path("code").asString()).isEqualTo("REQUEST_ID_REUSED");
        assertState(owner, 1, "first"); assertThat(receipts(owner)).isEqualTo(1);
    }

    @Test
    void reusedRequestMustReturn400AsRequiredByTheHttpContract() throws Exception {
        post(owner, draft("reused-contract", 0, "first"));
        var response = post(owner, draft("reused-contract", 0, "second"));
        assertState(owner, 1, "first"); assertThat(receipts(owner)).isEqualTo(1);
        error(response, 400, "REQUEST_ID_REUSED");
    }

    @Test void differentRequestIdsWithStaleRevisionReturn409WithoutReceipt() throws Exception {
        post(owner, draft("one", 0, "one")); post(owner, draft("two", 1, "two"));
        error(post(owner, draft("stale", 1, "lost")), 409, "REVISION_CONFLICT");
        assertState(owner, 2, "two"); assertThat(receipts(owner)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(longs = {0, 1})
    void failingReceiptInsertRollsBackTheDraftInTheSameTransaction(long revision) throws Exception {
        if (revision == 1) post(owner, draft("seed", 0, "seed"));
        jdbc.execute("ALTER TABLE practice_request_receipt ADD CONSTRAINT test_draft_receipt_failure CHECK (request_id <> 'fail-receipt')");
        try { error(post(owner, draft("fail-receipt", revision, "must-rollback")), 500, "INTERNAL_ERROR"); }
        finally { jdbc.execute("ALTER TABLE practice_request_receipt DROP CHECK test_draft_receipt_failure"); }
        if (revision == 0) assertThat(stateCount(owner)).isZero(); else assertState(owner, 1, "seed");
        assertThat(receipts(owner)).isEqualTo(revision);
    }

    @Test void sessionOwnerCannotSaveAnotherUsersCollectionAndForgedContextIsIgnored() throws Exception {
        Viewer other = viewer();
        var forged = draft("forged", 0, "mine");
        forged.put("userId", other.user()); forged.put("contextId", other.context()); forged.put("contentSha256", "00".repeat(32));
        error(send(other.path() + "/actions", owner.cookie(), ORIGIN, JSON.writeValueAsString(forged)), 404, "NOT_FOUND");
        assertThat(stateCount(other)).isZero(); assertThat(receipts(owner)).isZero();
        assertSaved(post(owner, forged), 1, "mine"); assertState(owner, 1, "mine");
        assertThat(stateCount(other)).isZero(); assertThat(receipts(other)).isZero();
        assertThat(jdbc.queryForObject("SELECT LOWER(HEX(content_sha256)) FROM practice_question_state WHERE context_id=?", String.class, owner.context()))
                .isEqualTo(jdbc.queryForObject("SELECT LOWER(HEX(content_sha256)) FROM content_question WHERE bank_id=? AND question_id=?", String.class, owner.bank(), owner.qid()));
    }

    @Test void readSaveReadRestoresAnswerRevisionStampAndCapabilities() throws Exception {
        var before = get(owner.path(), owner.cookie());
        assertThat(json(before).path("state").path("revision").asLong()).isZero();
        assertThat(json(before).path("capabilities").path("canSave").asBoolean()).isTrue();
        var body = draft("end-to-end", 0, "chosen"); body.put("contentVersion", json(before).path("stamp").path("contentVersion").asString());
        var saved = post(owner, body); assertSaved(saved, 1, "chosen");
        var after = get(owner.path(), owner.cookie());
        assertThat(json(after).path("state")).isEqualTo(json(saved).path("state"));
        assertThat(json(after).path("stamp")).isEqualTo(json(saved).path("stamp"));
        assertThat(after.body()).doesNotContain("\"correctOptionId\"");
        assertThat(saved.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "x65", "missing", "null"})
    void invalidRequestIdIs400AndWritesNothing(String value) throws Exception {
        var body = draft(value.equals("x65") ? "x".repeat(65) : value, 0, "invalid");
        if (value.equals("missing")) body.remove("requestId"); if (value.equals("null")) body.put("requestId", null);
        error(post(owner, body), 400, "VALIDATION_FAILED"); assertThat(stateCount(owner)).isZero(); assertThat(receipts(owner)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "-1", "1.5", "\"0\"", "9223372036854775808", "9223372036854775807"})
    void invalidRevisionIs400(String revision) throws Exception {
        var body = draft("bad-revision", 0, "invalid"); body.put("revision", JSON.readTree(revision));
        error(post(owner, body), 400, "VALIDATION_FAILED"); assertThat(stateCount(owner)).isZero();
    }

    @Test void maximumRequestIdIsAcceptedAndScopedByUser() throws Exception {
        Viewer other = viewer(); String id = "r".repeat(64);
        assertSaved(post(owner, draft(id, 0, "a")), 1, "a"); assertSaved(post(other, draft(id, 0, "b")), 1, "b");
        assertThat(receipts(owner)).isEqualTo(1); assertThat(receipts(other)).isEqualTo(1);
    }

    @Test void nullAnswerCanBeSavedButMissingAnswerCannot() throws Exception {
        var body = draft("empty-answer", 0, "unused"); body.put("data", Map.of());
        error(post(owner, body), 400, "VALIDATION_FAILED");
        body.put("data", Collections.singletonMap("answer", null));
        var saved = post(owner, body); assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(json(saved).path("state").path("answer").isNull()).isTrue();
    }

    @Test void unsupportedActionsAreExplicitAndSubmittedQuestionsStayReadOnly() throws Exception {
        var body = draft("unsupported", 0, "unused"); body.put("action", "unknown-action");
        error(post(owner, body), 501, "FEATURE_NOT_AVAILABLE"); assertThat(receipts(owner)).isZero();
        jdbc.update("""
                INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,projection,revision,content_sha256,updated_at)
                SELECT ?,question_id,?,'submitted','{"final":true}','{"frozen":true}',4,content_sha256,NOW(3) FROM content_question WHERE bank_id=?
                """, owner.context(), owner.user(), owner.bank());
        assertThat(json(get(owner.path(), owner.cookie())).path("capabilities").path("canSave").asBoolean()).isFalse();
        assertThat(json(get("/api/collections/bank/" + owner.collection(), owner.cookie())).path("readOnly").asBoolean()).isFalse();
        error(post(owner, draft("submitted", 4, "overwrite")), 409, "REVISION_CONFLICT");
        assertThat(receipts(owner)).isZero();
    }

    @Test void authenticationOriginContentVersionAndQuestionLookupAreEnforced() throws Exception {
        String body = JSON.writeValueAsString(draft("security", 0, "test"));
        error(send(owner.path() + "/actions", null, ORIGIN, body), 401, "UNAUTHENTICATED");
        error(send(owner.path() + "/actions", owner.cookie(), null, body), 403, "ORIGIN_REJECTED");
        error(send(owner.path() + "/actions", owner.cookie(), "https://evil.test", body), 403, "ORIGIN_REJECTED");
        var changed = draft("changed", 0, "test"); changed.put("contentVersion", "wrong");
        error(post(owner, changed), 409, "CONTENT_CONFLICT");
        error(send("/api/collections/bank/" + owner.collection() + "/questions/missing/actions", owner.cookie(), ORIGIN, body), 404, "NOT_FOUND");
        error(send(owner.path() + "/actions", owner.cookie(), ORIGIN, "not json"), 400, "VALIDATION_FAILED");
        assertThat(stateCount(owner)).isZero(); assertThat(receipts(owner)).isZero();
    }

    private Viewer viewer() throws Exception {
        var user = auth.register(Ids.uuidV7() + "@example.test", "草稿测试", "password-123", "draft-test");
        var rows = imports.importArchive(user.userId(), "single-choice.qbank", new ByteArrayInputStream(Files.readAllBytes(
                Path.of(System.getProperty("basedir", ".")).resolve("../samples/single-choice.qbank"))));
        var row = rows.getFirst();
        jdbc.update("""
                INSERT INTO admin_extension_review (digest,extension_id,version,status)
                SELECT e.digest,e.extension_id,e.version,'approved' FROM content_extension_package e
                JOIN content_question q ON q.extension_digest=e.digest WHERE q.bank_id=?
                ON DUPLICATE KEY UPDATE status='approved'
                """, row.bankId());
        String cookie = "__Host-qf_session=" + user.token();
        var overview = get("/api/collections/bank/" + row.id(), cookie); assertThat(overview.statusCode()).as(overview.body()).isEqualTo(200);
        assertThat(json(overview).path("readOnly").asBoolean()).isFalse();
        String context = jdbc.queryForObject("SELECT id FROM practice_context WHERE collection_id=?", String.class, row.id());
        Viewer viewer = new Viewer(user.userId(), cookie, row.id(), row.bankId(), context, json(overview).path("questions").get(0).path("id").asString());
        viewers.add(viewer); return viewer;
    }
    private Map<String,Object> draft(String requestId, long revision, String answer) {
        return new LinkedHashMap<>(Map.of("requestId", requestId, "revision", revision, "action", "draft", "data", Map.of("answer", Map.of("selectedOptionId", answer))));
    }
    private HttpResponse<String> post(Viewer viewer, Map<String,Object> body) throws Exception { return send(viewer.path() + "/actions", viewer.cookie(), ORIGIN, JSON.writeValueAsString(body)); }
    private HttpResponse<String> send(String path, String cookie, String origin, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie); if (origin != null) request.header("Origin", origin);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).path("error").path("code").asString()).isEqualTo(code);
    }
    private static void assertSaved(HttpResponse<String> response, long revision, String answer) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("revision").asLong()).isEqualTo(revision);
        assertThat(json(response).path("state").path("revision").asLong()).isEqualTo(revision);
        assertThat(json(response).path("state").path("status").asString()).isEqualTo("draft");
        assertThat(json(response).path("state").path("answer").path("selectedOptionId").asString()).isEqualTo(answer);
        assertThat(json(response).path("question").path("data").isObject()).isTrue();
    }
    private void assertState(Viewer viewer, long revision, String answer) {
        var state = jdbc.queryForMap("SELECT revision,answer FROM practice_question_state WHERE context_id=? AND question_id=?", viewer.context(), viewer.qid());
        assertThat(((Number)state.get("revision")).longValue()).isEqualTo(revision);
        assertThat(JSON.readTree((String)state.get("answer")).path("selectedOptionId").asString()).isEqualTo(answer);
    }
    private int receipts(Viewer viewer) { return jdbc.queryForObject("SELECT COUNT(*) FROM practice_request_receipt WHERE user_id=?", Integer.class, viewer.user()); }
    private int stateCount(Viewer viewer) { return jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE context_id=?", Integer.class, viewer.context()); }
    private static Path temporary() { try { return Files.createTempDirectory("qf-draft-http-"); } catch(Exception error) { throw new ExceptionInInitializerError(error); } }
    private static Path directory(String name) { try { return Files.createDirectory(ROOT.resolve(name)); } catch(Exception error) { throw new ExceptionInInitializerError(error); } }
    @TestConfiguration static class Gates { @Bean DraftSqlGate draftSqlGate() { return new DraftSqlGate(); } }
}
