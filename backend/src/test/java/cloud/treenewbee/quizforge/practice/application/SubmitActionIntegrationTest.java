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
class SubmitActionIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final String TOKEN = "submit-action-test";
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
        registry.add("qf.rule-runner.base-url", SubmitActionIntegrationTest::runnerUrl);
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired AuthService auth;
    @Autowired ImportService imports;
    @MockitoSpyBean SubmitGrader grader;
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
        reset(runner, grader);
        var registered = auth.register(Ids.uuidV7()+"@example.test", "提交接口", "password-123", "test");
        user = registered.userId(); cookie = "__Host-qf_session=" + registered.token();
        samples.clear();
        seeds.forEach((type, seed) -> {
            String id = Ids.uuidV7();
            jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?", id,user,seed.collection());
            samples.put(type, new Sample(id,seed.question()));
        });
    }
    @AfterEach void cleanupViewer() { reset(runner,grader); deleteUser(user); }
    private void deleteUser(String id) {
        for (String table : List.of("practice_request_receipt","practice_round_question","practice_round","practice_question_state","practice_context","practice_collection","auth_session"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id=?",id);
        jdbc.update("DELETE FROM account_user WHERE id=?",id);
    }
    @AfterAll void cleanup() throws Exception {
        deleteUser(seedUser); RUNNER.stop(); http.close();
        try(var files=Files.walk(ROOT)) { for(Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
    @ParameterizedTest @ValueSource(strings={"单选题","多选题","判断题","简答题","简答题（答题区）"})
    void directSubmitPreservesRealExtensionOutcomeAndRefresh(String type) throws Exception {
        var expected=grader.grade(user,samples.get(type).collection(),samples.get(type).question().questionId(),answer(type,true));
        if (type.equals("简答题（答题区）")) {
            assertThat(expected.gradingStatus()).isEqualTo("pending"); assertThat(expected.score()).isNull();
        } else {
            assertThat(expected.gradingStatus()).isEqualTo("graded");
            assertThat(expected.score()).isEqualByComparingTo(type.equals("简答题") ? BigDecimal.ZERO : expected.maxScore());
        }
        reset(runner,grader);
        var response=post(type,"submit","direct",0,answer(type,true));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode root=JSON.readTree(response.body()), result=root.path("state").path("result");
        assertThat(root.path("revision").asLong()).isEqualTo(2);
        assertThat(root.path("state").path("status").asString()).isEqualTo("submitted");
        assertThat(result.path("gradingStatus").asString()).isEqualTo(expected.gradingStatus());
        if(expected.score()==null) assertThat(result.path("score").isNull()).isTrue();
        else assertThat(result.path("score").decimalValue()).isEqualByComparingTo(expected.score());
        assertThat(root.path("question").path("data")).isEqualTo(JSON.readTree(expected.projectedJson()));
        assertThat(root.path("capabilities").path("canSubmit").asBoolean()).isFalse();
        var state=jdbc.queryForMap("SELECT grading_status,score,result,outline_states FROM practice_question_state WHERE user_id=?",user);
        assertThat(state.get("grading_status")).isEqualTo(expected.gradingStatus());
        assertThat(JSON.readTree((String)state.get("result"))).isEqualTo(JSON.readTree(expected.resultJson()));
        assertThat(state.get("outline_states") == null ? null : JSON.readTree((String)state.get("outline_states")))
                .isEqualTo(expected.outlineStatesJson() == null ? null : JSON.readTree(expected.outlineStatesJson()));
        assertThat((BigDecimal)state.get("score")).isEqualTo(expected.score()==null?null:expected.score().setScale(4));
        assertThat(JSON.readTree(get(type).body()).path("state")).isEqualTo(root.path("state"));
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
        String payload=jdbc.queryForObject("SELECT payload FROM practice_round_question WHERE user_id=?",String.class,user);
        assertThat(JSON.readTree(payload).path("question").path("data")).isEqualTo(JSON.readTree(expected.projectedJson()));
        assertThat(JSON.readTree(payload).path("state")).isEqualTo(root.path("state"));
    }
    @ParameterizedTest @ValueSource(strings={"单选题","多选题","判断题"})
    void wrongObjectiveAnswerIsGradedBelowFullScore(String type) throws Exception {
        var response=post(type,"submit","wrong",0,answer(type,false));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result=JSON.readTree(response.body()).path("state").path("result");
        assertThat(result.path("gradingStatus").asString()).isEqualTo("graded");
        assertThat(result.path("score").decimalValue()).isLessThan(result.path("maxScore").decimalValue());
    }
    @Test void replayIsIdenticalAndDoesNotGradeAgain() throws Exception {
        var first=post("单选题","submit","repeat",0,answer("单选题",true));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var replay=post("单选题","submit","repeat",0,answer("单选题",true));
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).isEqualTo(first.body());
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(2);
        error(post("单选题","submit","repeat",0,answer("单选题",false)),400,"REQUEST_ID_REUSED");
    }
    @Test void submittedQuestionCannotBeSubmittedOrSavedAgain() throws Exception {
        assertThat(post("单选题","submit","first",0,answer("单选题",true)).statusCode()).isEqualTo(200);
        error(post("单选题","submit","again",2,answer("单选题",false)),409,"REVISION_CONFLICT");
        error(post("单选题","draft","draft",2,answer("单选题",false)),409,"REVISION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(2);
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
    }
    @Test void gradingDoesNotHoldTransactionAndConcurrentDraftRejectsStaleResult() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(runner).execute(eq("submit"),any(),anyMap());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var submitting=pool.submit(()->post("单选题","submit","gated",0,answer("单选题",true)));
            try {
                assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();
                var draft=pool.submit(()->post("单选题","draft","changed",1,answer("单选题",false))).get(10,TimeUnit.SECONDS);
                assertThat(draft.statusCode()).as(draft.body()).isEqualTo(200);
            } finally { release.countDown(); }
            error(submitting.get(30,TimeUnit.SECONDS),409,"REVISION_CONFLICT");
        } finally { release.countDown(); }
        var row=jdbc.queryForMap("SELECT status,revision,answer,result FROM practice_question_state WHERE user_id=?",user);
        assertThat(row.get("status")).isEqualTo("draft"); assertThat(row.get("result")).isNull();
        assertThat(((Number)row.get("revision")).longValue()).isEqualTo(2);
        assertThat(JSON.readTree((String)row.get("answer"))).isEqualTo(answer("单选题",false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void resolveTargetSucceedsWithoutCallingUnavailableRunner() {
        doThrow(new RuleRunnerException("runner unavailable")).when(runner).execute(anyString(), any(), anyMap());
        doThrow(new RuleRunnerException("runner unavailable")).when(runner).execute(anyString(), any(), anyMap(), any());
        var sample = samples.get("单选题");
        var target = reads.resolveTarget(user, sample.collection(), sample.question().questionId());
        assertThat(target.contextId()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM practice_context WHERE user_id=? AND collection_id=?", String.class, user, sample.collection()));
        assertThat(target.contentSha256()).containsExactly(sample.question().contentSha256());
        assertThat(target.extensionDigest()).containsExactly(sample.question().extensionDigest());
        assertThat(reads.resolveTarget(user, sample.collection(), sample.question().questionId()).contextId())
                .isEqualTo(target.contextId());
        verifyNoInteractions(runner);
    }

    @Test void submitRunnerFailureReturns503AndKeepsSavedDraft() throws Exception {
        assertThat(get("单选题").statusCode()).isEqualTo(200);
        doThrow(new RuleRunnerException("unavailable")).when(runner).execute(eq("submit"),any(),anyMap());
        error(post("单选题","submit","unavailable",0,answer("单选题",true)),503,"RULE_UNAVAILABLE");
        var row=jdbc.queryForMap("SELECT status,revision,answer,result FROM practice_question_state WHERE user_id=?",user);
        assertThat(row.get("status")).isEqualTo("draft"); assertThat(row.get("result")).isNull();
        assertThat(((Number)row.get("revision")).longValue()).isEqualTo(1);
        assertThat(JSON.readTree((String)row.get("answer"))).isEqualTo(answer("单选题",true));
    }
    @Test void anotherUsersCollectionCannotBeSubmitted() throws Exception {
        var seed=seeds.get("单选题");
        var response=send(seed,"submit","idor",0,answer("单选题",true));
        error(response,404,"NOT_FOUND");
        verify(runner,never()).execute(eq("submit"),any(),anyMap());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void scoresUseIdenticalHalfUpRoundingInStorageReadAndSnapshot() throws Exception {
        var original=grader.grade(user,samples.get("单选题").collection(),samples.get("单选题").question().questionId(),answer("单选题",true));
        var decimal=new BigDecimal("1.23455");
        doReturn(new GradeOutcome(original.resultJson(),original.projectedJson(),decimal,decimal,"graded",original.outlineStatesJson()))
                .when(grader).grade(eq(user),anyString(),anyString(),any());
        var response=post("单选题","submit","precision",0,answer("单选题",true));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result=JSON.readTree(response.body()).path("state").path("result");
        assertThat(result.path("score").decimalValue()).isEqualByComparingTo("1.2346");
        assertThat(result.path("maxScore").decimalValue()).isEqualByComparingTo("1.2346");
        var row=jdbc.queryForMap("SELECT score,max_score FROM practice_question_state WHERE user_id=?",user);
        assertThat(row.get("score")).isEqualTo(new BigDecimal("1.2346")); assertThat(row.get("max_score")).isEqualTo(row.get("score"));
        assertThat(JSON.readTree(get("单选题").body()).path("state").path("result")).isEqualTo(result);
    }

    @Test void concurrentFirstSubmissionsShareOneRoundAndFreezeBothQuestions() throws Exception {
        var first=samples.get("单选题"); var q=first.question();
        String secondId="concurrent-"+Ids.uuidV7();
        var secondQuestion=new QuestionRow(q.bankId(),secondId,1,"第二题",q.extensionDigest(),q.outline(),q.listening(),q.data(),q.maxScore(),q.contentSha256(),1,"同名分组");
        questions.insert(secondQuestion);
        var second=new Sample(first.collection(),secondQuestion);
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        doAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); assertThat(release.await(30,TimeUnit.SECONDS)).isTrue(); return invocation.callRealMethod();
        }).when(runner).execute(eq("submit"),any(),anyMap());
        clearInvocations(reads, snapshots, readMapper);
        try {
            try(var pool=Executors.newFixedThreadPool(2)) {
                var a=pool.submit(()->send(first,"submit","round-a",0,answer("单选题",true)));
                var b=pool.submit(()->send(second,"submit","round-b",0,answer("单选题",false)));
                try { assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue(); }
                finally { release.countDown(); }
                var ar=a.get(45,TimeUnit.SECONDS); var br=b.get(45,TimeUnit.SECONDS);
                assertThat(ar.statusCode()).as(ar.body()).isEqualTo(200); assertThat(br.statusCode()).as(br.body()).isEqualTo(200);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isEqualTo(1);
            var context=jdbc.queryForMap("SELECT current_round_id,progress_version FROM practice_context WHERE user_id=?",user);
            var ids=jdbc.queryForList("SELECT round_id FROM practice_round_question WHERE user_id=?",String.class,user);
            assertThat(ids).containsExactly((String)context.get("current_round_id"),(String)context.get("current_round_id"));
            assertThat(((Number)context.get("progress_version")).longValue()).isEqualTo(2);
            var frozen=JSON.readTree(jdbc.queryForObject("SELECT collection_snapshot FROM practice_round WHERE user_id=?",String.class,user));
            assertThat(frozen.path("title").asString()).isEqualTo("单选题");
            assertThat(frozen.path("questions").get(0).path("id").asString()).isEqualTo(q.questionId());
            assertThat(frozen.path("questions").get(1).path("id").asString()).isEqualTo(secondId);
            assertThat(frozen.path("questions").get(1).path("outlineGroupIndex").asInt()).isEqualTo(1);
            assertThat(frozen.path("questions").get(1).path("contentSha256").asString()).isEqualTo(HexFormat.of().formatHex(q.contentSha256()));
            assertThat(frozen.toString()).doesNotContain("correctOptionId");
            verify(snapshots, times(1)).collection(user, first.collection());
            verify(reads, times(1)).collectionSnapshot(user, first.collection());
            verify(readMapper, times(1)).questions(eq(q.bankId()), anyString(), eq(user));
        } finally { release.countDown(); jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id=?",q.bankId(),secondId); }
    }

    @Test void onlyFirstSubmissionInRoundComputesCollectionSnapshot() throws Exception {
        var first = samples.get("单选题"); var q = first.question();
        String secondId = "later-" + Ids.uuidV7();
        var secondQuestion = new QuestionRow(q.bankId(), secondId, 1, "第二题", q.extensionDigest(), q.outline(),
                q.listening(), q.data(), q.maxScore(), q.contentSha256(), 1, "同名分组");
        questions.insert(secondQuestion);
        clearInvocations(reads, snapshots, readMapper);
        try {
            var initial = send(first, "submit", "first-in-round", 0, answer("单选题", true));
            assertThat(initial.statusCode()).as(initial.body()).isEqualTo(200);
            String frozen = jdbc.queryForObject("SELECT collection_snapshot FROM practice_round WHERE user_id=?", String.class, user);
            verify(snapshots, times(1)).collection(user, first.collection());
            verify(reads, times(1)).collectionSnapshot(user, first.collection());
            verify(readMapper, times(1)).questions(eq(q.bankId()), anyString(), eq(user));

            jdbc.update("UPDATE practice_collection SET title='轮中改名' WHERE id=?", first.collection());
            clearInvocations(reads, snapshots, readMapper);
            var later = send(new Sample(first.collection(), secondQuestion), "submit", "later-in-round", 0, answer("单选题", false));
            assertThat(later.statusCode()).as(later.body()).isEqualTo(200);
            verify(snapshots, never()).collection(anyString(), anyString());
            verify(reads, never()).collectionSnapshot(anyString(), anyString());
            verify(readMapper, never()).questions(anyString(), anyString(), anyString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?", Integer.class, user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE user_id=?", Integer.class, user)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT collection_snapshot FROM practice_round WHERE user_id=?", String.class, user)).isEqualTo(frozen);
            assertThat(JSON.readTree(frozen).path("title").asString()).isEqualTo("单选题");
        } finally {
            jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id=?", q.bankId(), secondId);
        }
    }

    @Test
    void coldProjectionRunnerFailureMustStillPreserveDraft() throws Exception {
        var original=samples.get("单选题"); var q=original.question();
        String id="cold-"+Ids.uuidV7(); byte[] hash=new byte[32]; new java.security.SecureRandom().nextBytes(hash);
        var cold=new QuestionRow(q.bankId(),id,1,"冷缓存",q.extensionDigest(),q.outline(),q.listening(),q.data(),q.maxScore(),hash,0,null);
        questions.insert(cold);
        doThrow(new RuleRunnerException("unavailable before projection")).when(runner).execute(eq("project"),any(),anyMap());
        try {
            error(send(new Sample(original.collection(),cold),"submit","cold",0,answer("单选题",true)),503,"RULE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=? AND question_id=? AND status='draft'",Integer.class,user,id)).isEqualTo(1);
        } finally { jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id=?",q.bankId(),id); }
    }

    @Test void collectionSnapshotKeepsCreationTitleAndExcludesAnswers() {
        var sample=samples.get("单选题");
        String snapshot = snapshots.collection(user, sample.collection());
        jdbc.update("UPDATE practice_collection SET title='改名后' WHERE id=?",sample.collection());
        var frozen=JSON.readTree(snapshot);
        assertThat(frozen.path("title").asString()).isEqualTo("单选题");
        assertThat(frozen.path("questions").size()).isEqualTo(1);
        var question=frozen.path("questions").get(0);
        assertThat(question.path("outline")).isEqualTo(JSON.readTree(sample.question().outline()));
        assertThat(question.path("contentSha256").asString()).isEqualTo(HexFormat.of().formatHex(sample.question().contentSha256()));
        assertThat(question.has("data")).isFalse(); assertThat(question.has("answer")).isFalse();
        assertThat(question.has("result")).isFalse();
    }
    @Test @Order(Integer.MAX_VALUE)
    void stoppedRealRunnerReturns503AndRetainsDraft() throws Exception {
        assertThat(get("单选题").statusCode()).isEqualTo(200);
        RUNNER.stop();
        reset(runner);
        var sample = samples.get("单选题");
        var target = reads.resolveTarget(user, sample.collection(), sample.question().questionId());
        assertThat(target.contextId()).isNotBlank();
        assertThat(target.contentSha256()).containsExactly(sample.question().contentSha256());
        assertThat(target.extensionDigest()).containsExactly(sample.question().extensionDigest());
        verifyNoInteractions(runner);
        error(post("单选题","submit","stopped",0,answer("单选题",true)),503,"RULE_UNAVAILABLE");
        var row=jdbc.queryForMap("SELECT status,revision,answer,result FROM practice_question_state WHERE user_id=?",user);
        assertThat(row.get("status")).isEqualTo("draft"); assertThat(row.get("result")).isNull();
        assertThat(((Number)row.get("revision")).longValue()).isEqualTo(1);
        assertThat(JSON.readTree((String)row.get("answer"))).isEqualTo(answer("单选题",true));
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
