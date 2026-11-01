package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionMapper;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionRow;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(120)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PracticeLifecycleIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final String TOKEN = "lifecycle-action-test";
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
            .withEnv("QF_RULE_WORKER_LIMIT", "2").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN))
            .withStartupTimeout(Duration.ofMinutes(2));
    static { RUNNER.start(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.security.allowed-origin", () -> "https://submit.example.test");
        registry.add("qf.storage.blob-dir", BLOBS::toString);
        registry.add("qf.import.temp-dir", UPLOADS::toString);
        registry.add("qf.rule-runner.base-url", PracticeLifecycleIntegrationTest::runnerUrl);
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired AuthService auth;
    @Autowired ImportService imports;
    @MockitoSpyBean SubmitGrader grader;
    @MockitoSpyBean ReviewGrader reviewGrader;
    @MockitoSpyBean cloud.treenewbee.quizforge.practice.infrastructure.PracticeLifecycleMapper lifecycleMapper;
    @MockitoSpyBean cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper contextMapper;
    @MockitoSpyBean cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper receiptMapper;
    @Autowired SubmitActionService actions;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();
    String seedUser, cookie;
    final Map<String, Sample> seeds = new HashMap<>();
    @MockitoSpyBean PracticeReadService reads;
    @MockitoSpyBean RoundSnapshots snapshots;
    @MockitoSpyBean PracticeReadMapper readMapper;
    @Autowired QuestionMapper questions;
    @Autowired JdbcTemplate jdbc;
    @Autowired cloud.treenewbee.quizforge.quota.application.QuotaService quotas;
    @Autowired org.springframework.transaction.support.TransactionTemplate quotaTransaction;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean RuleRunnerClient runner;
    String user;
    record Sample(String collection, QuestionRow question) {}
    final Map<String, Sample> samples = new HashMap<>();

    @BeforeAll void importImmutableSamples() throws Exception {
        user = auth.register(Ids.uuidV7() + "@example.test", "提交判分测试", "password-123", "grader-test").userId();
        var imported = imports.importArchive(user, "basic-types.qbank", new ByteArrayInputStream(Files.readAllBytes(
                Path.of(System.getProperty("basedir", ".")).resolve("../samples/basic-types.qbank"))));
        for (var collection : imported) samples.put(collection.title(), new Sample(collection.id(), questions.findByPosition(collection.bankId(), 0).orElseThrow()));
        assertThat(samples).hasSize(5);
        seedUser = user; seeds.putAll(samples);
        jdbc.update("""
                INSERT INTO admin_extension_review (digest,extension_id,version,status)
                SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' FROM content_extension_package e
                JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id
                WHERE c.user_id=? ON DUPLICATE KEY UPDATE status='approved'
                """, user);
    }


    @BeforeEach void viewer() {
        reset(runner, grader, reviewGrader, lifecycleMapper, contextMapper, receiptMapper);
        var registered = auth.register(Ids.uuidV7()+"@example.test", "提交接口", "password-123", "test");
        user = registered.userId(); cookie = "__Host-qf_session=" + registered.token();
        samples.clear();
        seeds.forEach((type, seed) -> {
            String id = Ids.uuidV7();
            jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?", id,user,seed.collection());
            samples.put(type, new Sample(id,seed.question()));
        });
    }
    @AfterEach void cleanupViewer() { reset(runner, grader, reviewGrader, lifecycleMapper, contextMapper, receiptMapper); deleteUser(user); }
    private void deleteUser(String id) {
        for (String table : List.of("practice_whiteboard","practice_request_receipt","practice_round_question","practice_round","practice_question_state","practice_context","practice_collection","auth_session"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id=?",id);
        jdbc.update("DELETE FROM account_user WHERE id=?",id);
    }
    @AfterAll void cleanup() throws Exception {
        deleteUser(seedUser); RUNNER.stop(); http.close();
        try(var files=Files.walk(ROOT)) { for(Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }

    @ParameterizedTest @ValueSource(strings={"单选题","多选题","判断题","简答题","简答题（答题区）"})
    void retryClearsLiveAnswerButPreservesFrozenSubmissionAndCanResubmit(String type) throws Exception {
        var submitted = ok(post(type,"submit","submit",0,answer(type,true)));
        var frozen = payload();
        var response = lifecycle(type,"retry","retry",2,JSON.createObjectNode());
        var value = ok(response);
        assertThat(value.path("revision").asLong()).isEqualTo(3);
        assertThat(value.path("state").path("status").asString()).isEqualTo("unanswered");
        assertThat(value.path("state").path("answer").isNull()).isTrue();
        assertThat(value.path("state").path("result").isNull()).isTrue();
        assertThat(value.path("capabilities").path("canSave").asBoolean()).isTrue();
        var row = jdbc.queryForMap("SELECT * FROM practice_question_state WHERE user_id=?", user);
        for (var key : List.of("answer","answer_state","result","score","max_score","outline_states","projection"))
            assertThat(row.get(key)).as(key).isNull();
        assertThat(row.get("grading_status")).isEqualTo("none");
        assertThat(((Number)row.get("grading_generation")).intValue()).isEqualTo(1);
        assertThat(payload()).isEqualTo(frozen);
        assertThat(JSON.readTree(get(type).body()).path("question").path("data").has("correctOptionId")).isFalse();
        var replay = lifecycle(type,"retry","retry",2,JSON.createObjectNode());
        assertThat(replay.body()).isEqualTo(response.body());
        error(lifecycle(type,"retry","retry",2,JSON.createObjectNode().put("changed",true)),400,"REQUEST_ID_REUSED");
        assertThat(ok(post(type,"submit","submit-again",3,answer(type,true))).path("revision").asLong()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        // Old retry still replays exactly, even after another submission advanced the live state.
        assertThat(lifecycle(type,"retry","retry",2,JSON.createObjectNode()).body()).isEqualTo(response.body());
    }

    @Test void practiceQuotaTracksSubmissionReviewRetryAndRestartWithoutChargingReplay() throws Exception {
        String type="简答题";
        ok(post(type,"submit","quota-submit",0,answer(type,true)));
        long submitted=quotas.usage(user).storage().usedBytes(); assertThat(submitted).isPositive();
        assertPracticeQuotaEqualsPayloadBytes();
        ok(post(type,"submit","quota-submit",0,answer(type,true)));
        assertThat(quotas.usage(user).storage().usedBytes()).isEqualTo(submitted);
        ok(lifecycle(type,"review","quota-review",2,reviewData(1.5,part(type))));
        assertPracticeQuotaEqualsPayloadBytes();
        ok(lifecycle(type,"retry","quota-retry",3,JSON.createObjectNode()));
        assertPracticeQuotaEqualsPayloadBytes();
        assertThat(quotas.usage(user).storage().usedBytes()).isPositive(); // Frozen historical submission survives retry.
        ok(post(type,"submit","quota-again",4,answer(type,true)));
        String context=jdbc.queryForObject("SELECT id FROM practice_context WHERE user_id=?",String.class,user);
        String ink=JSON.writeValueAsString("x".repeat(10000));
        jdbc.update("INSERT INTO practice_whiteboard VALUES (?, 'q1', ?, ?, 1, NOW(3))",context,user,ink);
        quotaTransaction.executeWithoutResult(s -> quotas.setStorage(user,"whiteboard",context+":q1",samples.get(type).collection(),ink.length()));
        jdbc.update("UPDATE quota_account SET storage_limit=storage_used WHERE user_id=?",user);
        ok(restart(type,"quota-restart",ok(summary(type))));
        assertPracticeQuotaEqualsPayloadBytes();
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM quota_storage_item WHERE user_id=? AND category='state'",Long.class,user)).isZero();
        assertThat(quotas.usage(user).storage().usedBytes()).isPositive(); // Historical snapshot and summary remain billed.
    }

    @Test void quotaFailureDuringFinalSubmissionRollsBackResultRoundAndReceiptButKeepsPreviouslySavedDraft() throws Exception {
        String type="单选题";
        ok(post(type,"draft","quota-draft",0,answer(type,true)));
        long draftBytes=quotas.usage(user).storage().usedBytes();
        jdbc.update("UPDATE quota_account SET storage_limit=? WHERE user_id=?",draftBytes,user);
        error(post(type,"submit","quota-too-full",1,answer(type,true)),413,"STORAGE_QUOTA_EXCEEDED");
        var state=jdbc.queryForMap("SELECT status,revision,result FROM practice_question_state WHERE user_id=?",user);
        assertThat(state.get("status")).isEqualTo("draft"); assertThat(((Number)state.get("revision")).longValue()).isEqualTo(2);
        assertThat(state.get("result")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_request_receipt WHERE user_id=? AND request_id='quota-too-full'",Integer.class,user)).isZero();
        assertThat(quotas.usage(user).storage().usedBytes()).isEqualTo(draftBytes);
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
    }

    private void assertPracticeQuotaEqualsPayloadBytes() {
        long bytes=0;
        for (var row:jdbc.queryForList("SELECT answer,answer_state,result,outline_states,projection FROM practice_question_state WHERE user_id=?",user))
            for(var value:row.values())if(value!=null)bytes+=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (var row:jdbc.queryForList("SELECT collection_snapshot,summary FROM practice_round WHERE user_id=? AND deleted_at IS NULL",user))
            for(var value:row.values())if(value!=null)bytes+=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (String payload:jdbc.queryForList("SELECT payload FROM practice_round_question WHERE user_id=?",String.class,user))
            bytes+=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(quotas.usage(user).storage().usedBytes()).isEqualTo(bytes);
    }

    @ParameterizedTest @ValueSource(strings={"简答题","简答题（答题区）"})
    void reviewUsesRealExtensionAndLastSuccessfulScoreUpdatesStateAndSnapshot(String type) throws Exception {
        ok(post(type,"submit","submit",0,answer(type,true)));
        assertThat(JSON.readTree(get(type).body()).path("capabilities").path("canReview").asBoolean()).isTrue();
        String target = JSON.readTree(samples.get(type).question().data()).path("parts").get(0).path("id").asString();
        var data = reviewData(1.5,target);
        var response = lifecycle(type,"review","review-1",2,data);
        var first = ok(response);
        assertThat(first.path("state").path("result").path("score").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(first.path("state").path("result").path("gradingSource").asString()).isEqualTo("manual");
        assertThat(first.path("state").path("outlineStates").isArray()).isTrue();
        var frozen = JSON.readTree(payload());
        assertThat(frozen.path("state")).isEqualTo(first.path("state"));
        assertThat(frozen.path("question").path("data")).isEqualTo(first.path("question").path("data"));
        assertThat(lifecycle(type,"review","review-1",2,data).body()).isEqualTo(response.body());
        verify(runner,times(1)).execute(eq("review"),any(),anyMap());
        var last = ok(lifecycle(type,"review","review-2",3,reviewData(0,target)));
        assertThat(last.path("state").path("result").path("score").decimalValue()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT grading_generation FROM practice_question_state WHERE user_id=?",Integer.class,user)).isEqualTo(2);
        assertThat(JSON.readTree(get(type).body()).path("state")).isEqualTo(last.path("state"));
        assertThat(lifecycle(type,"review","review-1",2,data).body()).isEqualTo(response.body());
        error(lifecycle(type,"review","review-1",2,reviewData(2,target)),400,"REQUEST_ID_REUSED");
    }

    @Test void completedRoundRejectsRetryButAllowsReviewAndUpdatesFrozenSummary() throws Exception {
        String type="简答题";
        ok(post(type,"submit","submit",0,answer(type,true)));
        jdbc.update("UPDATE practice_round SET status='completed',completed_at=NOW(3),summary=JSON_OBJECT('score',0) WHERE user_id=?",user);
        error(lifecycle(type,"retry","retry",2,JSON.createObjectNode()),409,"PRACTICE_COMPLETED");
        var caps=JSON.readTree(get(type).body()).path("capabilities");
        assertThat(caps.path("canRetry").asBoolean()).isFalse(); assertThat(caps.path("canReview").asBoolean()).isTrue();
        ok(lifecycle(type,"review","review",2,reviewData(1.5,part(type))));
        var summary=JSON.readTree(jdbc.queryForObject("SELECT summary FROM practice_round WHERE user_id=?",String.class,user));
        assertThat(summary.path("finished").asBoolean()).isTrue();
        assertThat(summary.path("score").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(ok(summary(type)).path("score")).isEqualTo(summary.path("score"));
    }

    @Test void completedRetryRejectsBeforeLoadingProjectionEvenWhenRunnerIsUnavailable() throws Exception {
        String type="简答题";var sample=samples.get(type);ok(post(type,"submit","submit",0,answer(type,true)));
        jdbc.update("UPDATE practice_round SET status='completed' WHERE user_id=?",user);
        clearInvocations(reads,runner);
        doThrow(new RuleRunnerException("projection unavailable")).when(reads).prepareDraft(user,sample.collection(),sample.question().questionId());
        try {
            error(lifecycle(type,"retry","retry",2,JSON.createObjectNode()),409,"PRACTICE_COMPLETED");
            verify(reads,never()).prepareDraft(anyString(),anyString(),anyString());verifyNoInteractions(runner);
        } finally {reset(reads);}
    }

    @ParameterizedTest @ValueSource(strings={"retry","review"})
    void unsubmittedAndStaleRevisionRejectWithoutWrites(String action) throws Exception {
        String type="简答题";
        var data=action.equals("review")?reviewData(1,part(type)):JSON.createObjectNode();
        error(lifecycle(type,action,"none",0,data),409,"REVISION_CONFLICT");
        ok(post(type,"draft","draft",0,answer(type,true)));
        error(lifecycle(type,action,"on-draft",1,data),409,"REVISION_CONFLICT");
        ok(post(type,"submit","submit",1,answer(type,true)));
        error(lifecycle(type,action,"stale",2,data),409,"REVISION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_request_receipt WHERE user_id=?",Integer.class,user)).isEqualTo(2);
    }

    @Test void objectiveTypeDoesNotAdvertiseManualReviewAndRunnerRejectionKeepsSubmittedState() throws Exception {
        ok(post("单选题","submit","submit",0,answer("单选题",true)));
        assertThat(JSON.readTree(get("单选题").body()).path("capabilities").path("canReview").asBoolean()).isFalse();
        error(lifecycle("单选题","review","review",2,reviewData(1,null)),409,"REVIEW_UNAVAILABLE");
        ok(post("简答题","submit","short-submit",0,answer("简答题",true)));
        error(lifecycle("简答题","review","bad-target",2,reviewData(1,"nonexistent-part")),422,"RULE_REJECTED");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=? AND question_id=? AND context_id=(SELECT id FROM practice_context WHERE collection_id=?)",Long.class,user,
                samples.get("简答题").question().questionId(),samples.get("简答题").collection())).isEqualTo(2);
    }

    @Test void summaryAggregatesAllTypesPendingIsNullAndReadsNeverExecuteRules() throws Exception {
        // A private test bank gathers one question of each type without changing the shared fixtures.
        String bank=Ids.uuidV7(), collection=samples.get("单选题").collection();
        jdbc.update("INSERT INTO content_bank (id,content_sha256,source_title,description,format_version,features,listening,question_count,created_at) SELECT ?,RANDOM_BYTES(32),source_title,description,format_version,features,listening,5,created_at FROM content_bank WHERE id=?",bank,samples.get("单选题").question().bankId());
        int position=0;
        for(var type:List.of("单选题","多选题","判断题","简答题","简答题（答题区）")) {
            var q=samples.get(type).question();
            String qid="mixed-"+(position++);
            questions.insert(new QuestionRow(bank,qid,position,q.title(),q.extensionDigest(),q.outline(),q.listening(),q.data(),q.maxScore(),q.contentSha256(),q.outlineGroupIndex(),q.outlineGroupName()));
            samples.put(type,new Sample(collection,questions.findById(bank,qid).orElseThrow()));
        }
        jdbc.update("UPDATE practice_collection SET bank_id=? WHERE id=?",bank,collection);
        try {
            ok(post("单选题","submit","single",0,answer("单选题",true)));
            ok(post("简答题（答题区）","submit","pending",0,answer("简答题（答题区）",true)));
            ok(post("多选题","draft","draft",0,answer("多选题",true)));
            clearInvocations(runner);
            var response=summary("单选题"); var value=ok(response);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(value.path("questionCount").asInt()).isEqualTo(5);
            assertThat(value.path("submittedCount").asInt()).isEqualTo(2);
            assertThat(value.path("gradedCount").asInt()).isEqualTo(1);
            assertThat(value.path("pendingCount").asInt()).isEqualTo(1);
            assertThat(value.path("answeredUnsubmittedCount").asInt()).isEqualTo(1);
            assertThat(value.path("questions").get(4).path("score").isNull()).isTrue();
            assertThat(value.path("questions").get(2).path("score").isNull()).isTrue();
            assertThat(value.path("score").decimalValue()).isEqualByComparingTo(samples.get("单选题").question().maxScore());
            var expected=samples.values().stream().map(s->s.question().maxScore()).reduce(BigDecimal.ZERO,BigDecimal::add);
            assertThat(value.path("maxScore").decimalValue()).isEqualByComparingTo(expected);
            assertThat(value.path("summaryVersion").asString()).hasSize(64);
            assertThat(response.body()).doesNotContain("correctOptionId","answerAndExplanation","responses");
            assertThat(summary("单选题").body()).isEqualTo(response.body()); verifyNoInteractions(runner);
        } finally { jdbc.update("DELETE FROM content_question WHERE bank_id=?",bank); jdbc.update("DELETE FROM content_bank WHERE id=?",bank); }
    }

    @Test void restartFreezesInProgressRoundResetsInPlaceDeletesWhiteboardAndReplaysExactly() throws Exception {
        String type="单选题";
        ok(post(type,"submit","submit",0,answer(type,true)));
        var before=ok(summary(type)); String frozen=payload();
        String context=jdbc.queryForObject("SELECT id FROM practice_context WHERE user_id=?",String.class,user);
        jdbc.update("INSERT INTO practice_whiteboard VALUES (?,?,?,JSON_OBJECT('strokes',JSON_ARRAY()),10,NOW(3))",context,samples.get(type).question().questionId(),user);
        var response=restart(type,"restart",before); assertThat(ok(response).path("restarted").asBoolean()).isTrue();
        assertThat(restart(type,"restart",before).body()).isEqualTo(response.body());
        var row=jdbc.queryForMap("SELECT * FROM practice_question_state WHERE user_id=?",user);
        assertThat(row.get("status")).isEqualTo("unanswered"); assertThat(((Number)row.get("revision")).longValue()).isEqualTo(3);
        assertThat(((Number)row.get("grading_generation")).intValue()).isEqualTo(1);
        for(var key:List.of("answer","answer_state","result","score","max_score","outline_states","projection")) assertThat(row.get(key)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_whiteboard WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_round_id FROM practice_context WHERE user_id=?",String.class,user)).isNull();
        assertThat(jdbc.queryForObject("SELECT progress_version FROM practice_context WHERE user_id=?",Long.class,user)).isEqualTo(2);
        var old=jdbc.queryForMap("SELECT status,summary,completed_at FROM practice_round WHERE user_id=?",user);
        assertThat(old.get("status")).isEqualTo("completed"); assertThat(old.get("completed_at")).isNotNull();
        assertThat(JSON.readTree((String)old.get("summary")).path("score")).isEqualTo(before.path("score"));
        assertThat(payload()).isEqualTo(frozen);
        assertThat(ok(summary(type)).path("pendingCount").asInt()).isZero();
        error(post(type,"draft","old-tab",2,answer(type,false)),409,"REVISION_CONFLICT");
        ok(post(type,"submit","new-round",3,answer(type,false)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isEqualTo(2);
        assertThat(restart(type,"restart",before).body()).isEqualTo(response.body());
        error(restart(type,"restart",ok(summary(type))),400,"REQUEST_ID_REUSED");
    }

    @Test void restartWithoutRoundResetsDraftAndDraftRevisionInvalidatesSummaryVersion() throws Exception {
        String type="判断题";
        var before=ok(summary(type)); assertThat(before.path("roundId").isNull()).isTrue();
        ok(post(type,"draft","draft",0,answer(type,true)));
        error(restart(type,"stale",before),409,"SUMMARY_CONFLICT");
        var current=ok(summary(type)); assertThat(current.path("summaryVersion")).isNotEqualTo(before.path("summaryVersion"));
        ok(restart(type,"restart",current));
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"retry","review","summary","restart"})
    void allLifecycleEndpointsRejectOtherUsersCollection(String action) throws Exception {
        var other=seeds.get("简答题");
        var body=action.equals("restart")?JSON.readTree("{\"requestId\":\"idor\",\"roundId\":null,\"summaryVersion\":\"stale\"}")
                :JSON.valueToTree(Map.of("requestId","idor","revision",0,"action",action,"data",Map.of("review",Map.of("score",1))));
        String url=action.equals("summary")||action.equals("restart")?"/api/collections/bank/"+other.collection()+"/"+action:path(other)+"/actions";
        error(request(url,action.equals("summary")?null:body),404,"NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Integer.class,user)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"retry","review","restart"})
    void receiptFailureRollsBackEveryLifecycleMutation(String action) throws Exception {
        String type="简答题"; ok(post(type,"submit","submit",0,answer(type,true)));
        String frozen=payload(); var row=jdbc.queryForMap("SELECT * FROM practice_question_state WHERE user_id=?",user);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("test receipt failure")).when(receiptMapper)
                .insert(eq(user),eq("fail"),any(),anyString());
        var response=action.equals("restart")?restart(type,"fail",ok(summary(type)))
                :lifecycle(type,action,"fail",2,action.equals("review")?reviewData(1,part(type)):JSON.createObjectNode());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
        assertThat(jdbc.queryForMap("SELECT * FROM practice_question_state WHERE user_id=?",user)).usingRecursiveComparison().isEqualTo(row);
        assertThat(payload()).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT status FROM practice_round WHERE user_id=?",String.class,user)).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject("SELECT progress_version FROM practice_context WHERE user_id=?",Long.class,user)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"retry","review","restart"})
    void duplicateConcurrentRequestHasOneMutationAndIdenticalResponses(String action) throws Exception {
        String type="简答题"; ok(post(type,"submit","submit",0,answer(type,true)));
        var summary=ok(summary(type)); var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        var realContext = sqlSession.getMapper(cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper.class);
        doAnswer(call->{entered.countDown();assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();return realContext.lockForUpdate(call.getArgument(0),call.getArgument(1));})
                .when(contextMapper).lockForUpdate(anyString(),eq(user));
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> call=()->action.equals("restart")?restart(type,"same",summary)
                    :lifecycle(type,action,"same",2,action.equals("review")?reviewData(1,part(type)):JSON.createObjectNode());
            var a=pool.submit(call);var b=pool.submit(call);
            try {assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();} finally {release.countDown();}
            var ar=a.get(45,TimeUnit.SECONDS);var br=b.get(45,TimeUnit.SECONDS);
            ok(ar);ok(br); assertThat(br.body()).isEqualTo(ar.body());
        } finally {release.countDown();}
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_request_receipt WHERE user_id=? AND request_id='same'",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT progress_version FROM practice_context WHERE user_id=?",Long.class,user)).isEqualTo(2);
    }

    @Test void concurrentReviewsAtSameRevisionHaveOneWinnerAndNoLockDuringGrading() throws Exception {
        String type="简答题"; ok(post(type,"submit","submit",0,answer(type,true)));
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
            assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
                .when(reviewGrader).grade(anyString(),anyString(),anyString(),any(),any(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->lifecycle(type,"review","a",2,reviewData(1,part(type))));
            var b=pool.submit(()->lifecycle(type,"review","b",2,reviewData(2,part(type))));
            try {assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();} finally {release.countDown();}
            assertThat(List.of(a.get(45,TimeUnit.SECONDS).statusCode(),b.get(45,TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200,409);
        } finally {release.countDown();}
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT grading_generation FROM practice_question_state WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }

    @Test void restartWhileSubmissionIsGradingRejectsOldResultWithoutCreatingRound() throws Exception {
        String type="单选题"; var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
                .when(runner).execute(eq("submit"),any(),anyMap());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var submitting=pool.submit(()->post(type,"submit","submitting",0,answer(type,true)));
            try {
                assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();
                var summary=ok(summary(type)); assertThat(summary.path("answeredUnsubmittedCount").asInt()).isEqualTo(1);
                ok(pool.submit(()->restart(type,"restart",summary)).get(10,TimeUnit.SECONDS));
            } finally {release.countDown();}
            error(submitting.get(45,TimeUnit.SECONDS),409,"REVISION_CONFLICT");
        } finally {release.countDown();}
        assertThat(jdbc.queryForObject("SELECT status FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("unanswered");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isZero();
    }

    @Test void restartWhileReviewIsGradingRejectsOldReviewAndPreservesOldHistory() throws Exception {
        String type="简答题";ok(post(type,"submit","submit",0,answer(type,true)));
        var before=ok(summary(type));String frozen=payload();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
                .when(reviewGrader).grade(anyString(),anyString(),anyString(),any(),any(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reviewing=pool.submit(()->lifecycle(type,"review","reviewing",2,reviewData(1,part(type))));
            try {assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();ok(pool.submit(()->restart(type,"restart",before)).get(10,TimeUnit.SECONDS));}
            finally {release.countDown();}
            error(reviewing.get(45,TimeUnit.SECONDS),409,"REVISION_CONFLICT");
        } finally {release.countDown();}
        assertThat(payload()).isEqualTo(frozen);
    }

    @Test void reviewUnavailableRunnerReturns503WithoutChangingSubmission() throws Exception {
        String type="简答题";ok(post(type,"submit","submit",0,answer(type,true)));String frozen=payload();
        doThrow(new RuleRunnerException("offline")).when(runner).execute(eq("review"),any(),anyMap());
        error(lifecycle(type,"review","offline",2,reviewData(1,part(type))),503,"RULE_UNAVAILABLE");
        assertThat(payload()).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(2);
    }

    @Test void reviewRoundsBothScoresHalfUpInStateResponseAndFrozenSnapshot() throws Exception {
        String type="简答题";ok(post(type,"submit","submit",0,answer(type,true)));
        var original=reviewGrader.grade(user,samples.get(type).collection(),samples.get(type).question().questionId(),answer(type,true),
                JSON.readTree(get(type).body()).path("state"),reviewData(1,part(type)).get("review"));
        var number=new BigDecimal("1.23455");
        doReturn(new GradeOutcome(original.resultJson(),original.projectedJson(),number,number,"graded",original.outlineStatesJson()))
                .when(reviewGrader).grade(anyString(),anyString(),anyString(),any(),any(),any());
        var value=ok(lifecycle(type,"review","rounding",2,reviewData(1,part(type))));
        assertThat(value.path("state").path("result").path("score").decimalValue()).isEqualByComparingTo("1.2346");
        assertThat(value.path("state").path("result").path("maxScore").decimalValue()).isEqualByComparingTo("1.2346");
        assertThat(jdbc.queryForObject("SELECT score=max_score FROM practice_question_state WHERE user_id=?",Boolean.class,user)).isTrue();
        assertThat(JSON.readTree(payload()).path("state")).isEqualTo(value.path("state"));
    }

    @Test void restartChecksDraftCommittedWhileItWasWaitingBeforeTakingStateLocks() throws Exception {
        String type="单选题";ok(post(type,"draft","draft",0,answer(type,true)));
        var before=ok(summary(type));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var real=sqlSession.getMapper(cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper.class);
        doAnswer(call->{var value=real.lockForUpdate(call.getArgument(0),call.getArgument(1));locked.countDown();
            assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();return value;}).when(contextMapper).lockForUpdate(anyString(),eq(user));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var restarting=pool.submit(()->restart(type,"restart",before));
            try {
                assertThat(locked.await(30,TimeUnit.SECONDS)).isTrue();
                ok(pool.submit(()->post(type,"draft","new-draft",1,answer(type,false))).get(10,TimeUnit.SECONDS));
            } finally {release.countDown();}
            error(restarting.get(30,TimeUnit.SECONDS),409,"SUMMARY_CONFLICT");
        } finally {release.countDown();}
        assertThat(jdbc.queryForObject("SELECT status FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(2);
    }

    @Test void originalSubmissionReplayRemainsIdenticalAfterRetryReviewAndRestart() throws Exception {
        String type="简答题";
        var first=post(type,"submit","original",0,answer(type,true));ok(first);
        ok(lifecycle(type,"review","review",2,reviewData(1,part(type))));
        assertThat(post(type,"submit","original",0,answer(type,true)).body()).isEqualTo(first.body());
        ok(lifecycle(type,"retry","retry",3,JSON.createObjectNode()));
        assertThat(post(type,"submit","original",0,answer(type,true)).body()).isEqualTo(first.body());
        ok(restart(type,"restart",ok(summary(type))));
        assertThat(post(type,"submit","original",0,answer(type,true)).body()).isEqualTo(first.body());
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
    }

    @ParameterizedTest @ValueSource(strings={"retry","restart"})
    void concurrentDifferentRequestsHaveOneWinnerAndOne409(String action) throws Exception {
        String type="单选题";ok(post(type,"submit","submit",0,answer(type,true)));
        var before=ok(summary(type));var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var real=sqlSession.getMapper(cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper.class);
        doAnswer(call->{entered.countDown();assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();
            return real.lockForUpdate(call.getArgument(0),call.getArgument(1));}).when(contextMapper).lockForUpdate(anyString(),eq(user));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->action.equals("restart")?restart(type,"a",before):lifecycle(type,"retry","a",2,JSON.createObjectNode()));
            var b=pool.submit(()->action.equals("restart")?restart(type,"b",before):lifecycle(type,"retry","b",2,JSON.createObjectNode()));
            try {assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();} finally {release.countDown();}
            assertThat(List.of(a.get(45,TimeUnit.SECONDS).statusCode(),b.get(45,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
        } finally {release.countDown();}
    }

    @ParameterizedTest @ValueSource(strings={"retry","review","summary","restart"})
    void revokedApprovalRejectsLifecycleEndpointsBeforeAnyRuleOrWrite(String action) throws Exception {
        var sample=samples.get("简答题");
        jdbc.update("UPDATE admin_extension_review SET status='revoked' WHERE digest=?",sample.question().extensionDigest());
        try {
            var response=action.equals("summary")?summary("简答题"):action.equals("restart")
                    ?restart("简答题","revoked",JSON.readTree("{\"roundId\":null,\"summaryVersion\":\"stale\"}"))
                    :lifecycle("简答题",action,"revoked",0,reviewData(1,part("简答题")));
            error(response,422,"CLOUD_EXTENSION_REVIEW_REQUIRED");verifyNoInteractions(runner);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_request_receipt WHERE user_id=?",Integer.class,user)).isZero();
        } finally {jdbc.update("UPDATE admin_extension_review SET status='approved' WHERE digest=?",sample.question().extensionDigest());}
    }

    @Test void invalidLifecycleBodiesAndOriginAreRejected() throws Exception {
        var sample=samples.get("简答题");String url=path(sample)+"/actions";
        for(String body:List.of("{\"requestId\":\"\",\"revision\":0,\"action\":\"retry\",\"data\":{}}",
                "{\"requestId\":\"a\",\"revision\":-1,\"action\":\"retry\",\"data\":{}}",
                "{\"requestId\":\"a\",\"revision\":0,\"action\":\"retry\"}",
                "{\"requestId\":\"a\",\"revision\":0,\"action\":\"review\",\"data\":{}}",
                "{\"requestId\":\"a\",\"revision\":0,\"action\":\"review\",\"data\":{\"review\":{\"score\":\"1\"}}}"))
            error(request(url,JSON.readTree(body)),400,"VALIDATION_FAILED");
        error(request(url,JSON.valueToTree(Map.of("requestId","x".repeat(65),"revision",0,"action","retry","data",Map.of()))),400,"VALIDATION_FAILED");
        for(String body:List.of("{\"requestId\":\"a\",\"summaryVersion\":\"stale\"}",
                "{\"requestId\":\"a\",\"roundId\":1,\"summaryVersion\":\"stale\"}",
                "{\"requestId\":\"a\",\"roundId\":null,\"summaryVersion\":\"\"}"))
            error(request("/api/collections/bank/"+sample.collection()+"/restart",JSON.readTree(body)),400,"VALIDATION_FAILED");
        var write=HttpRequest.newBuilder(URI.create("http://localhost:"+port+url)).header("Cookie",cookie).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"a\",\"revision\":0,\"action\":\"retry\",\"data\":{}}"));
        error(http.send(write.build(),HttpResponse.BodyHandlers.ofString()),403,"ORIGIN_REJECTED");
        error(http.send(write.header("Origin","https://other.test").build(),HttpResponse.BodyHandlers.ofString()),403,"ORIGIN_REJECTED");
        var anon=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/collections/bank/"+sample.collection()+"/summary")).GET().build();
        error(http.send(anon,HttpResponse.BodyHandlers.ofString()),401,"UNAUTHENTICATED");
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return JSON.readTree(response.body());
    }
    private String part(String type) {return JSON.readTree(samples.get(type).question().data()).path("parts").get(0).path("id").asString();}
    private JsonNode reviewData(double score,String target) {
        var data=JSON.createObjectNode();var review=data.putObject("review");review.put("score",score);
        if(target!=null) review.put("targetId",target);return data;
    }
    private String payload() {return jdbc.queryForObject("SELECT payload FROM practice_round_question WHERE user_id=?",String.class,user);}
    private HttpResponse<String> lifecycle(String type,String action,String id,long revision,JsonNode data) throws Exception {
        return request(path(samples.get(type))+"/actions",JSON.valueToTree(Map.of("requestId",id,"revision",revision,"action",action,"data",data)));
    }
    private HttpResponse<String> summary(String type) throws Exception {return request("/api/collections/bank/"+samples.get(type).collection()+"/summary",null);}
    private HttpResponse<String> restart(String type,String id,JsonNode summary) throws Exception {
        var body=JSON.createObjectNode().put("requestId",id);body.set("roundId",summary.get("roundId"));body.set("summaryVersion",summary.get("summaryVersion"));
        return request("/api/collections/bank/"+samples.get(type).collection()+"/restart",body);
    }
    private HttpResponse<String> request(String path,JsonNode body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(90))
                .header("Cookie",cookie).header("Origin","https://submit.example.test").header("Content-Type","application/json");
        if(body==null)builder.GET();else builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String type,String action,String id,long revision,JsonNode answer) throws Exception {
        return send(samples.get(type),action,id,revision,answer);
    }
    private HttpResponse<String> send(Sample sample,String action,String id,long revision,JsonNode answer) throws Exception {
        var body=Map.of("requestId",id,"revision",revision,"action",action,"data",Map.of("answer",answer));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path(sample)+"/actions"))
                .timeout(Duration.ofSeconds(90)).header("Cookie",cookie).header("Origin","https://submit.example.test")
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String type) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path(samples.get(type)))).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private String path(Sample sample) { return "/api/collections/bank/"+sample.collection()+"/questions/"+sample.question().questionId(); }
    private void error(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo(code);
    }
    private JsonNode answer(String type, boolean correct) {
        JsonNode data = JSON.readTree(samples.get(type).question().data());
        if (type.equals("单选题")) return JSON.valueToTree(Map.of("selectedOptionId", correct ? data.path("correctOptionId").asString() : data.path("options").get(0).path("id").asString()));
        if (type.equals("多选题")) {
            var wrong = JSON.createArrayNode();
            for (JsonNode option : data.path("options")) {
                boolean isCorrect = false;
                for (JsonNode id : data.path("correctOptionIds")) if (id.equals(option.get("id"))) isCorrect = true;
                if (!isCorrect) { wrong.add(option.path("id").asString()); break; }
            }
            if (wrong.isEmpty()) throw new AssertionError("The sample must have a valid but incorrect option");
            return JSON.valueToTree(Map.of("selectedOptionIds", correct ? data.get("correctOptionIds") : wrong));
        }
        if (type.equals("判断题")) return JSON.valueToTree(Map.of("value", correct == data.path("correctAnswer").asBoolean()));
        Map<String, Object> responses = new LinkedHashMap<>();
        for (JsonNode part : data.path("parts")) {
            String id = part.path("id").asString(); String text = correct ? "缓存减少重复计算，需要注意一致性和过期数据" : "缓存让计算变慢且永远不需要更新";
            Object value = type.equals("简答题") ? JSON.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}")
                    : Map.of("type", "qf-answer-area", "formatVersion", 1, "id", "response-" + id, "width", 760,
                            "height", part.path("answerHeight").asInt(480), "strokes", List.of(), "images", List.of(), "text", text);
            responses.put(id, value);
        }
        return JSON.valueToTree(Map.of("responses", responses));
    }
    private static String runnerUrl() { return "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090); }
    private static Path temporary() { try { return Files.createTempDirectory("qf-submit-grader-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static Path directory(String name) { try { return Files.createDirectory(ROOT.resolve(name)); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
}
