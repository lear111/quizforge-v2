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
class PracticeCompletionHistoryIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final String TOKEN = "lifecycle-action-test";
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
            .withEnv("QF_RULE_WORKER_LIMIT", "8").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN))
            .withStartupTimeout(Duration.ofMinutes(2));
    static { RUNNER.start(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.practice.jobs.enabled",()->"false");
        registry.add("qf.security.allowed-origin", () -> "https://submit.example.test");
        registry.add("qf.storage.blob-dir", BLOBS::toString);
        registry.add("qf.import.temp-dir", UPLOADS::toString);
        registry.add("qf.rule-runner.base-url", PracticeCompletionHistoryIntegrationTest::runnerUrl);
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired PracticeLifecycleJobs jobs;
    @Autowired PracticeHistoryService histories;
    @Autowired cloud.treenewbee.quizforge.quota.application.PrivateCollectionService deletion;
    @Autowired cloud.treenewbee.quizforge.content.infrastructure.BlobStore blobs;
    @MockitoSpyBean cloud.treenewbee.quizforge.practice.infrastructure.PracticeHistoryMapper historyMapper;
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
    @MockitoSpyBean cloud.treenewbee.quizforge.content.application.ContentPages pages;
    String user;
    record Sample(String collection, QuestionRow question) {}
    final Map<String, Sample> samples = new HashMap<>();

    @BeforeAll void importImmutableSamples() throws Exception {
        // 种子注册先于基类 BeforeEach；单独运行本类时也要显式初始化测试策略。
        jdbc.update("""
                UPDATE auth_policy SET login_code=FALSE, login_captcha=FALSE, registration_code=FALSE,
                    registration_captcha=FALSE, invitation_required=FALSE WHERE id=1
                """);
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
        clearInvocations(reads);
        var registered = auth.register(Ids.uuidV7()+"@example.test", "提交接口", "password-123", "test");
        user = registered.userId(); cookie = "__Host-qf_session=" + registered.token();
        samples.clear();
        seeds.forEach((type, seed) -> {
            String id = Ids.uuidV7();
            jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?", id,user,seed.collection());
            samples.put(type, new Sample(id,seed.question()));
        });
    }
    @AfterEach void cleanupViewer() { reset(runner, grader, reviewGrader, lifecycleMapper, contextMapper, receiptMapper, pages);
        clearInvocations(reads); deleteUser(user); }
    private void deleteUser(String id) {
        jdbc.update("DELETE a FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id WHERE r.user_id=?",id);
        for (String table : List.of("practice_lifecycle_job","practice_history_annotation","practice_user_resource","practice_whiteboard","practice_request_receipt","practice_round_question","practice_round","practice_question_state","practice_context","practice_collection","auth_session"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id=?",id);
        jdbc.update("DELETE FROM account_user WHERE id=?",id);
    }
    @AfterAll void cleanup() throws Exception {
        deleteUser(seedUser); RUNNER.stop(); http.close();
        try(var files=Files.walk(ROOT)) { for(Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }

    @ParameterizedTest @ValueSource(strings={"单选题","多选题","判断题","简答题","简答题（答题区）"})
    void finishGradesSavedAnswersAndArchiveFreezesOnlyOnRestart(String type) throws Exception {
        ok(post(type,"draft","draft",0,answer(type,true)));
        var initial=ok(summary(type));
        var accepted=begin(type,"finish","finish",initial);assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(begin(type,"finish","finish",initial).body()).isEqualTo(accepted.body());
        error(post(type,"draft","locked",1,answer(type,false)),409,"PRACTICE_LOCKED");
        var completed=complete(accepted);assertThat(completed.path("finished").asBoolean()).isTrue();
        var current=ok(get(type));
        assertThat(current.path("state").path("status").asString()).isEqualTo("submitted");
        boolean pending=type.equals("简答题（答题区）");
        assertThat(current.path("state").path("result").path("gradingStatus").asString()).isEqualTo(pending?"pending":"graded");
        assertThat(completed.path("score").decimalValue()).isEqualByComparingTo(type.contains("简答")?BigDecimal.ZERO:samples.get(type).question().maxScore());
        assertThat(current.path("state").path("result").path("score").isNull()).isEqualTo(pending);
        assertThat(ok(request(base(type)+"/history",null)).path("records")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE user_id=?",Integer.class,user)).isZero();
        verify(reads,never()).collectionSnapshot(eq(user),anyString());
        var before=ok(summary(type));var archive=begin(type,"restart","archive",before);assertThat(archive.statusCode()).isEqualTo(202);
        assertThat(begin(type,"restart","archive",before).body()).isEqualTo(archive.body());
        complete(archive);
        verify(reads,times(1)).collectionSnapshot(user,samples.get(type).collection());
        var records=ok(request(base(type)+"/history",null)).path("records");assertThat(records).hasSize(1);
        assertThat(records.get(0).has("questions")).isFalse();assertThat(records.get(0).has("collectionSnapshot")).isFalse();
        var history=ok(request(base(type)+"/history/"+records.get(0).path("id").asString(),null));
        assertThat(history.path("snapshotVersion").asInt()).isEqualTo(1);assertThat(history.path("questions")).hasSize(1);
        assertThat(history.path("questions").get(0).path("payload").path("state")).isEqualTo(current.path("state"));
        assertThat(history.path("questions").get(0).path("payload").path("practiceSignals").path("attemptCount").asInt()).isEqualTo(1);
        assertThat(ok(get(type)).path("state").path("status").asString()).isEqualTo("unanswered");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void historyContainsUnansweredQuestionsFrozenOrderAndLateNotes(boolean emptyDraft) throws Exception {
        String type="单选题";var q=samples.get(type).question();
        jdbc.update("INSERT INTO content_question(bank_id,question_id,position,title,extension_digest,outline,listening,data,max_score,content_sha256) SELECT bank_id,'unanswered',1,'未作答',extension_digest,outline,listening,data,max_score,content_sha256 FROM content_question WHERE bank_id=? AND question_id=?",q.bankId(),q.questionId());
        try {
            if(emptyDraft)ok(request(base(type)+"/questions/unanswered/actions",JSON.valueToTree(Map.of(
                    "requestId","empty-draft","revision",0,"action","draft","data",Map.of("answer",JSON.nullNode())))));
            ok(post(type,"submit","submit",0,answer(type,true)));
            complete(begin(type,"finish","finish",ok(summary(type))));
            var doc=board("late-note");ok(lifecycle(type,"whiteboard","note",0,JSON.valueToTree(Map.of("layer","notes","draft",doc))));
            var archive=complete(begin(type,"restart","archive",ok(summary(type))));String id=archive.path("historyId").asString();
            var history=ok(request(base(type)+"/history/"+id,null));
            assertThat(history.path("collection").path("questions").get(1).path("id").asString()).isEqualTo("unanswered");
            assertThat(history.path("questions").get(1).path("payload").path("state").path("status").asString()).isEqualTo("unanswered");
            assertThat(history.path("questions").get(1).path("payload").path("question").path("data").toString()).doesNotContain("correctOptionId");
            assertThat(history.path("questions").get(0).path("payload").path("whiteboard").path("notes").path("strokes")).hasSize(1);
            assertThat(history.path("summary").path("questionCount").asInt()).isEqualTo(2);
        } finally {jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id='unanswered'",q.bankId());}
    }
    @Test void failureKeepsDraftsUnlocksAndCanBeRetriedWithoutDuplicateGrading() throws Exception {
        String type="单选题";ok(post(type,"draft","draft",0,answer(type,true)));
        var accepted=begin(type,"finish","finish",ok(summary(type)));
        doThrow(new RuleRunnerException("unavailable")).when(grader).grade(eq(user),anyString(),anyString(),any());
        jobs.tick();var failure=progress(accepted);
        assertThat(failure.path("status").asString()).isEqualTo("failed");assertThat(failure.path("error").path("code").asString()).isEqualTo("RULE_UNAVAILABLE");
        assertThat(ok(get(type)).path("state").path("status").asString()).isEqualTo("draft");
        ok(post(type,"draft","editable-again",1,answer(type,true)));reset(grader);
        complete(begin(type,"finish","retry-finish",ok(summary(type))));
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void gradingIsOutsideTransactionAndOtherCollectionSubmissionRemainsAvailable() throws Exception {
        String type="单选题";ok(post(type,"draft","draft",0,answer(type,true)));
        var accepted=begin(type,"finish","finish",ok(summary(type)));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
                .when(grader).grade(eq(user),eq(samples.get(type).collection()),anyString(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var working=pool.submit(jobs::tick);
            try {assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
                error(pool.submit(()->post(type,"draft","locked",1,answer(type,true))).get(5,TimeUnit.SECONDS),409,"PRACTICE_LOCKED");
                ok(pool.submit(()->post("判断题","submit","foreground",0,answer("判断题",true))).get(15,TimeUnit.SECONDS));
            } finally {release.countDown();}
            working.get(25,TimeUnit.SECONDS);
        } finally {release.countDown();}
        complete(accepted);
    }
    @Test void unfinishedRestartAndStaleFinishAreRejected() throws Exception {
        String type="单选题";var before=ok(summary(type));
        error(begin(type,"restart","bad",before),409,"PRACTICE_NOT_FINISHED");
        ok(post(type,"draft","draft",0,answer(type,true)));
        error(begin(type,"finish","stale",before),409,"SUMMARY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_lifecycle_job WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void sameFinishRequestConcurrentlyCreatesOneTask() throws Exception {
        String type="单选题";ok(post(type,"draft","draft",0,answer(type,true)));var before=ok(summary(type));
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> call=()->{ready.countDown();assertThat(go.await(10,TimeUnit.SECONDS)).isTrue();return begin(type,"finish","same",before);};
            var a=pool.submit(call);var b=pool.submit(call);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(202);assertThat(second.body()).isEqualTo(first.body());complete(first);
        } finally {go.countDown();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_lifecycle_job WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        verify(grader,times(1)).grade(eq(user),anyString(),anyString(),any());
    }
    @Test void annotationsAreEditableVersionedIdempotentAndDoNotChangeFrozenDraftOrAnswer() throws Exception {
        String type="简答题";ok(lifecycle(type,"whiteboard","frozen",0,JSON.valueToTree(Map.of("layer","draft","draft",board("frozen")))));
        ok(post(type,"submit","submit",0,answer(type,true)));complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        var before=ok(request(base(type)+"/history/"+id,null));String url=base(type)+"/history/"+id+"/questions/"+samples.get(type).question().questionId()+"/annotations";
        var body=JSON.valueToTree(Map.of("requestId","note","revision",0,"notes",board("one")));
        var first=request(url,body);ok(first);assertThat(request(url,body).body()).isEqualTo(first.body());
        error(request(url,JSON.valueToTree(Map.of("requestId","note","revision",0,"notes",board("two")))),400,"REQUEST_ID_REUSED");
        error(request(url,JSON.valueToTree(Map.of("requestId","conflict","revision",0,"notes",board("two")))),409,"REVISION_CONFLICT");
        ok(request(url,JSON.valueToTree(Map.of("requestId","edit","revision",1,"notes",board("two")))));
        var erase=JSON.valueToTree(Map.of("requestId","erase","revision",2,"notes",board(null)));
        var erased=request(url,erase);ok(erased);assertThat(request(url,erase).body()).isEqualTo(erased.body());
        error(request(url,JSON.valueToTree(Map.of("requestId","frozen-id","revision",3,"notes",board("frozen")))),400,"VALIDATION_FAILED");
        var after=ok(request(base(type)+"/history/"+id,null));assertThat(after.path("questions").get(0).path("payload").path("state")).isEqualTo(before.path("questions").get(0).path("payload").path("state"));
        assertThat(after.path("questions").get(0).path("payload").path("whiteboard").path("notes").path("strokes")).isEmpty();
        assertThat(after.path("questions").get(0).path("payload").path("whiteboard").path("draft"))
                .isEqualTo(before.path("questions").get(0).path("payload").path("whiteboard").path("draft"));
    }
    @Test void archivedHistoryDoesNotUseCurrentBankOrRunnerAndEnforcesOwnership() throws Exception {
        String type="单选题";complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        clearInvocations(runner,readMapper);var oldTitle=ok(request(base(type)+"/history/"+id,null)).path("collectionTitle").asString();
        jdbc.update("UPDATE practice_collection SET title='changed' WHERE id=?",samples.get(type).collection());
        var value=ok(request(base(type)+"/history/"+id,null));assertThat(value.path("collectionTitle").asString()).isEqualTo(oldTitle);
        verifyNoInteractions(runner);verify(readMapper,never()).questions(anyString(),anyString(),anyString());
        String ownCookie=cookie;var other=auth.register(Ids.uuidV7()+"@test.example","其他用户","password-123","test");cookie="__Host-qf_session="+other.token();
        try {error(request(base(type)+"/history/"+id,null),404,"NOT_FOUND");error(request(base(type)+"/jobs/"+id,null),404,"NOT_FOUND");}
        finally {cookie=ownCookie;deleteUser(other.userId());}
        var deleted=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+base(type)+"/history/"+id)).header("Cookie",cookie).header("Origin","https://submit.example.test").DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);assertThat(ok(request(base(type)+"/history",null)).path("records")).isEmpty();error(request(base(type)+"/history/"+id,null),404,"NOT_FOUND");
    }
    @Test void removedExtensionPageKeepsHistoryAndRegistersReadablePlaceholder() throws Exception {
        String type="单选题";ok(post(type,"submit","submitted",0,answer(type,true)));
        complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        doThrow(new java.io.IOException("removed")).when(pages).page(eq(user),anyString(),anyString());
        var history=ok(request(base(type)+"/history/"+id,null));
        var page=history.path("pages").properties().iterator().next().getValue();
        assertThat(page.path("html").asString()).contains("该资源已被管理员移除");
        assertThat(page.path("script").asString()).contains("QF.page.register({onLoad", "textContent=value.question.title", "JSON.stringify(value.answer", "JSON.stringify(value.result");
        assertThat(history.path("questions").get(0).path("payload").path("state").path("status").asString()).isEqualTo("submitted");
    }
    @Test void archiveFailureDoesNotResetAnswersAndNewRequestCanResume() throws Exception {
        String type="单选题";complete(begin(type,"finish","finish",ok(summary(type))));
        var accepted=begin(type,"restart","archive",ok(summary(type)));
        doThrow(new RuleRunnerException("offline")).when(runner).execute(eq("project"),any(),anyMap());
        // 强制模拟未命中；不可变投影可能已被其他测试放入缓存。
        doThrow(new RuleRunnerException("offline")).when(reads).question(eq(user),eq(samples.get(type).collection()),anyString(),any());
        jobs.tick();assertThat(progress(accepted).path("status").asString()).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT completion_status FROM practice_context WHERE user_id=?",String.class,user)).isEqualTo("completed");
        assertThat(ok(request(base(type)+"/history",null)).path("records")).isEmpty();reset(reads,runner);
        complete(begin(type,"restart","archive-retry",ok(summary(type))));assertThat(ok(request(base(type)+"/history",null)).path("records")).hasSize(1);
    }
    @Test void expiredWorkerLeaseCanBeRecovered() throws Exception {
        String type="判断题";ok(post(type,"draft","draft",0,answer(type,true)));var accepted=begin(type,"finish","finish",ok(summary(type)));
        jdbc.update("UPDATE practice_lifecycle_job SET status='running',lease_owner=?,lease_until='2000-01-01 00:00:00' WHERE user_id=?",Ids.uuidV7(),user);
        assertThat(complete(accepted).path("finished").asBoolean()).isTrue();
    }
    @Test void lastAttemptSignalsAndManualSourceAreFrozenWithoutEarlierAttempts() throws Exception {
        String type="简答题";ok(post(type,"submit","one",0,answer(type,true)));
        ok(lifecycle(type,"retry","retry",2,JSON.createObjectNode()));ok(post(type,"submit","two",3,answer(type,false)));
        complete(begin(type,"finish","finish",ok(summary(type))));
        ok(lifecycle(type,"review","manual",5,reviewData(2,part(type))));
        String id=complete(begin(type,"restart","restart",ok(summary(type)))).path("historyId").asString();
        var payload=ok(request(base(type)+"/history/"+id,null)).path("questions").get(0).path("payload");
        assertThat(payload.path("practiceSignals").path("attemptCount").asInt()).isEqualTo(2);
        assertThat(payload.path("practiceSignals").path("firstCorrect").asBoolean(true)).isFalse();
        assertThat(payload.path("practiceSignals").path("gradingSource").asString()).isEqualTo("manual");
        assertThat(payload.path("state").path("answer")).isEqualTo(answer(type,false));
        assertThat(payload.path("state").path("result").path("score").decimalValue()).isEqualByComparingTo("2");
    }
    @Test void completedUnansweredStampCannotReuseReadonlyCapabilitiesAfterRestart() throws Exception {
        String type="判断题";complete(begin(type,"finish","finish",ok(summary(type))));var old=ok(get(type));
        assertThat(old.path("capabilities").path("canSave").asBoolean()).isFalse();
        complete(begin(type,"restart","archive",ok(summary(type))));
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path(samples.get(type))))
            .header("Cookie",cookie).header("X-QuizForge-Question-Stamp",old.path("stamp").toString()).GET().build(),HttpResponse.BodyHandlers.ofString());
        var fresh=ok(response);assertThat(fresh.path("unchanged").asBoolean(false)).isFalse();
        assertThat(fresh.path("capabilities").path("canSave").asBoolean()).isTrue();
    }
    @Test void historyListDoesNotLoadPayloadAndAnnotationIdorCannotWrite() throws Exception {
        String type="单选题";complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        clearInvocations(historyMapper);ok(request(base(type)+"/history",null));
        verify(historyMapper,never()).entries(anyString(),anyString());
        verify(historyMapper,never()).find(anyString(),anyString(),anyString());
        var other=auth.register(Ids.uuidV7()+"@example.test","其他用户","password-123","test");String saved=cookie;
        cookie="__Host-qf_session="+other.token();
        try{error(request(base(type)+"/history/"+id+"/questions/"+samples.get(type).question().questionId()+"/annotations",
            JSON.valueToTree(Map.of("requestId","idor","revision",0,"notes",board("intruder")))),404,"NOT_FOUND");}
        finally {cookie=saved;deleteUser(other.userId());}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_history_annotation WHERE history_id=?",Integer.class,id)).isZero();
    }
    @Test void partialFinishFailureKeepsSuccessfulGradesAndRetryOnlyGradesRemainingDrafts() throws Exception {
        String type="判断题";var q=samples.get(type).question();
        jdbc.update("INSERT INTO content_question(bank_id,question_id,position,title,extension_digest,outline,listening,data,max_score,content_sha256) SELECT bank_id,'second',1,'第二题',extension_digest,outline,listening,data,max_score,content_sha256 FROM content_question WHERE bank_id=? AND question_id=?",q.bankId(),q.questionId());
        try {
            ok(post(type,"draft","one",0,answer(type,true)));
            ok(request(base(type)+"/questions/second/actions",JSON.valueToTree(Map.of("requestId","two","revision",0,"action","draft","data",Map.of("answer",answer(type,true))))));
            var accepted=begin(type,"finish","finish",ok(summary(type)));jobs.tick();
            doThrow(new RuleRunnerException("offline")).when(grader).grade(eq(user),anyString(),eq("second"),any());jobs.tick();
            assertThat(progress(accepted).path("status").asString()).isEqualTo("failed");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=? AND status='submitted'",Integer.class,user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=? AND status='draft'",Integer.class,user)).isEqualTo(1);
            reset(grader);complete(begin(type,"finish","retry",ok(summary(type))));
            verify(runner,times(2)).execute(eq("submit"),any(),anyMap());
        } finally {jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id='second'",q.bankId());}
    }
    @Test void historyAssetsRemainAuthorizedAfterBankChangesUntilHistoryIsDeleted() throws Exception {
        String type="单选题";var sample=samples.get(type);byte[] digest=sample.question().extensionDigest();
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=");
        String hash=blobs.put(png);jdbc.update("INSERT INTO content_bank_asset(bank_id,path,blob_sha256) VALUES(?,?,?)",sample.question().bankId(),"retained.png",HexFormat.of().parseHex(hash));
        try {
            complete(begin(type,"finish","finish",ok(summary(type))));String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
            jdbc.update("UPDATE practice_collection SET bank_id=? WHERE id=?",samples.get("多选题").question().bankId(),sample.collection());
            jdbc.update("UPDATE admin_extension_review SET status='revoked' WHERE digest=?",digest);
            var image=request("/api/resources/"+hash,null);assertThat(image.statusCode()).isEqualTo(200);assertThat(image.headers().firstValue("X-Accel-Redirect")).isPresent();
            assertThat(ok(request(base(type)+"/history/"+id,null)).path("pages").size()).isEqualTo(1);
            histories.delete(user,sample.collection(),id);error(request("/api/resources/"+hash,null),404,"NOT_FOUND");
        } finally {jdbc.update("UPDATE admin_extension_review SET status='approved' WHERE digest=?",digest);jdbc.update("DELETE FROM content_bank_asset WHERE bank_id=? AND path='retained.png'",sample.question().bankId());}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentHistoryNotesRespectRevisionAndIdenticalRequestReplay(boolean sameRequest) throws Exception {
        String type="单选题";complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        String url=base(type)+"/history/"+id+"/questions/"+samples.get(type).question().questionId()+"/annotations";
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> first=()->{ready.countDown();assertThat(go.await(10,TimeUnit.SECONDS)).isTrue();return request(url,JSON.valueToTree(Map.of("requestId","a","revision",0,"notes",board("one"))));};
            java.util.concurrent.Callable<HttpResponse<String>> second=()->{ready.countDown();assertThat(go.await(10,TimeUnit.SECONDS)).isTrue();return request(url,JSON.valueToTree(Map.of("requestId",sameRequest?"a":"b","revision",0,"notes",board("one"))));};
            var a=pool.submit(first);var b=pool.submit(second);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            var ar=a.get(20,TimeUnit.SECONDS);var br=b.get(20,TimeUnit.SECONDS);
            if(sameRequest){assertThat(ar.statusCode()).isEqualTo(200);assertThat(br.body()).isEqualTo(ar.body());}
            else assertThat(List.of(ar.statusCode(),br.statusCode())).containsExactlyInAnyOrder(200,409);
        } finally {go.countDown();}
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_history_annotation WHERE history_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void oldPartialHistoryIsLabeledLegacyInsteadOfInventingMissingQuestions() throws Exception {
        String type="单选题";complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        jdbc.update("UPDATE practice_round SET collection_snapshot=JSON_REMOVE(collection_snapshot,'$.snapshotVersion') WHERE id=?",id);
        var detail=ok(request(base(type)+"/history/"+id,null));
        assertThat(detail.path("legacy").asBoolean()).isTrue();assertThat(detail.path("knownQuestionCount").asInt()).isEqualTo(1);
        assertThat(detail.path("snapshotVersion").asInt()).isZero();
    }
    @Test void deletingCollectionRemovesHistoryNotesJobsAndResourceReferencesTogether() throws Exception {
        String type="单选题";complete(begin(type,"finish","finish",ok(summary(type))));
        String id=complete(begin(type,"restart","archive",ok(summary(type)))).path("historyId").asString();
        histories.annotate(user,samples.get(type).collection(),id,samples.get(type).question().questionId(),"note",0,board("one"));
        deletion.delete(user,samples.get(type).collection());
        for(String table:List.of("practice_round_question","practice_round","practice_history_annotation","practice_lifecycle_job"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,user)).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_history_asset WHERE history_id=?",Integer.class,id)).isZero();
        error(request(base(type)+"/history/"+id,null),404,"NOT_FOUND");
    }

    @Test void reclaimedLeaseFencesOldWorkerBeforeAnyGradeCommit() throws Exception {
        String type="判断题";ok(post(type,"draft","draft",0,answer(type,true)));var accepted=begin(type,"finish","finish",ok(summary(type)));
        String id=JSON.readTree(accepted.body()).path("jobId").asString();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
            .when(grader).grade(eq(user),anyString(),anyString(),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var working=pool.submit(jobs::tick);
            try {assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();jdbc.update("UPDATE practice_lifecycle_job SET lease_owner=? WHERE id=?",Ids.uuidV7(),id);}
            finally {release.countDown();}working.get(20,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertThat(jdbc.queryForObject("SELECT status FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT completion_status FROM practice_context WHERE user_id=?",String.class,user)).isEqualTo("finishing");
        jdbc.update("UPDATE practice_lifecycle_job SET lease_until='2000-01-01 00:00:00' WHERE id=?",id);reset(grader);complete(accepted);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM practice_question_state WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }

    private JsonNode board(String id) {
        var doc=JSON.createObjectNode().put("schemaVersion",1);doc.set("viewport",JSON.valueToTree(Map.of("x",0,"y",0,"zoom",1)));doc.set("paper",JSON.valueToTree(Map.of("color","#ffffff","pattern","plain")));
        var strokes=doc.putArray("strokes");if(id!=null)strokes.add(JSON.valueToTree(Map.of("id",id,"color","#334155","width",2,"points",List.of(Map.of("x",1,"y",2,"pressure",.5)))));return doc;
    }
    private String base(String type) {return "/api/collections/bank/"+samples.get(type).collection();}
    private HttpResponse<String> begin(String type,String operation,String id,JsonNode summary) throws Exception {
        var body=JSON.createObjectNode().put("requestId",id);body.set("roundId",summary.get("roundId"));body.set("summaryVersion",summary.get("summaryVersion"));return request(base(type)+"/"+operation,body);
    }
    private JsonNode progress(HttpResponse<String> accepted) throws Exception {
        var value=JSON.readTree(accepted.body());String id=value.path("jobId").asString();
        String collection=jdbc.queryForObject("SELECT collection_id FROM practice_lifecycle_job WHERE id=?",String.class,id);
        return ok(request("/api/collections/bank/"+collection+"/jobs/"+id,null));
    }
    private JsonNode complete(HttpResponse<String> accepted) throws Exception {
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        for(int i=0;i<1000;i++){jobs.tick();var value=progress(accepted);if(value.path("status").asString().equals("completed"))return value.get("result");assertThat(value.path("status").asString()).as(value.toString()).isNotEqualTo("failed");}
        throw new AssertionError("Task did not complete");
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
