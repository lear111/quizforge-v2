package cloud.treenewbee.quizforge.ai;

import cloud.treenewbee.quizforge.practice.grading.*;
import cloud.treenewbee.quizforge.ai.grading.*;
import cloud.treenewbee.quizforge.ai.application.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.infrastructure.*;
import cloud.treenewbee.quizforge.exam.*;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.*;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Timeout(120)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties="qf.grading.mode=server")
@Import(AiGradingIntegrationTest.TimeConfig.class)
class AiGradingIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temp(), BLOBS = dir("blobs"), UPLOADS = dir("uploads");
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", "browser-test").withEnv("QF_BLOB_DIR", "/blobs")
            .withEnv("QF_RULE_WORKER_LIMIT", "8").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", "browser-test"))
            .withStartupTimeout(Duration.ofMinutes(2));
    static { RUNNER.start(); }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-03T12:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock browserClock() { return new MutableClock(); }
    }
    static final FakeUpstream UPSTREAM = upstream();
    static FakeUpstream upstream() {try {return new FakeUpstream();} catch(Exception e) {throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("qf.ai.base-url", UPSTREAM::baseUrl);
        r.add("qf.ai.model", () -> "fake-grading");
        r.add("qf.ai.call-timeout", () -> "10s");
        r.add("qf.security.allowed-origin", () -> "http://browser.example.test");
        r.add("qf.storage.blob-dir", BLOBS::toString);
        r.add("qf.import.temp-dir", UPLOADS::toString);
        r.add("qf.rule-runner.base-url", () -> "http://"+RUNNER.getHost()+":"+RUNNER.getMappedPort(8090));
        r.add("qf.rule-runner.token", () -> "browser-test");
        r.add("qf.rule-runner.default-deadline", () -> "60s");
    }
    @Autowired AuthService auth;
    @Autowired LegacyPracticeImportService imports;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionMapper questions;
    @Autowired BankMapper banks;
    @Autowired BrowserGradingService service;
    @Autowired PracticeReadService reads;
    @Autowired PracticeLifecycleJobs jobs;
    @Autowired PracticeSummaryService summaries;
    @Autowired DraftActionService drafts;
    @Autowired ExamService exams;
    @Autowired ExamDeadlineScanner scanner;
    @Autowired MutableClock clock;
    @MockitoSpyBean RuleRunnerClient runner;
    @Autowired SubmitGrader grader;
    @Autowired SubmitService submits;
    @Autowired AiGradingService ai;
    @Autowired AiGradingJobs aiJobs;
    @MockitoSpyBean AiGradingMapper aiMapper;
    @MockitoSpyBean PublishedVersions versions;
    @Autowired AiBalanceService balances;
    @Autowired UpstreamKeyService keys;
    @Autowired PracticeLifecycleService lifecycle;
    @Autowired cloud.treenewbee.quizforge.quota.application.PrivateCollectionService privateCollections;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    String key;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();
    record Sample(String collection, QuestionRow question) {}
    final Map<String,Sample> seeds = new HashMap<>(), samples = new HashMap<>();
    final List<String> viewers = new ArrayList<>();
    final List<String> extraBanks = new ArrayList<>();
    String seedUser, user, cookie;

    @BeforeAll void seed() throws Exception {
        jdbc.update("UPDATE auth_policy SET login_code=FALSE,login_captcha=FALSE,registration_code=FALSE,registration_captcha=FALSE,invitation_required=FALSE");
        seedUser = auth.register(Ids.uuidV7()+"@example.test", "浏览器样例", "password123", "test").userId();
        for (var item : imports.importArchive(seedUser, "basic-types.qbank", new ByteArrayInputStream(Files.readAllBytes(
                Path.of(System.getProperty("basedir", ".")).resolve("../samples/basic-types.qbank"))))) {
            seeds.put(item.title(), new Sample(item.id(), questions.findByPosition(item.bankId(), 0).orElseThrow()));
        }
        jdbc.update("""
                INSERT INTO admin_extension_review(digest,extension_id,version,status)
                SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' FROM content_extension_package e
                JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id
                WHERE c.user_id=? ON DUPLICATE KEY UPDATE status='approved'
                """, seedUser);
    }

    @BeforeEach void viewer() {
        reset(runner, aiMapper, versions); UPSTREAM.reset(); jdbc.update("DELETE FROM ai_upstream_key"); clock.now = Instant.parse("2026-11-03T12:00:00Z");
        var account = auth.register(Ids.uuidV7()+"@example.test", "浏览器判分", "password123", "test");
        user = account.userId(); cookie = "__Host-qf_session="+account.token(); viewers.add(user);
        jdbc.update("UPDATE account_user SET is_admin=TRUE WHERE id=?", user);
        key = "sk-fake-grading-" + Ids.uuidV7();
        keys.add(user, key, "本机假评分", 30000, 90000, null);
        samples.clear();
        seeds.forEach((name, seed) -> {
            String id = Ids.uuidV7();
            jdbc.update("INSERT INTO practice_collection(id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?",
                    id, user, seed.collection());
            samples.put(name, new Sample(id, seed.question()));
        });
    }

    @AfterEach void cleanup() {
        UPSTREAM.holdRelease.countDown(); reset(runner, aiMapper, versions);
        for (String viewer : viewers) deleteUser(viewer);
        viewers.clear();
        for (String bank : extraBanks) {jdbc.update("DELETE FROM content_question WHERE bank_id=?",bank);jdbc.update("DELETE FROM content_bank WHERE id=?",bank);}
        extraBanks.clear();
    }
    @AfterAll void close() throws Exception {
        deleteUser(seedUser); RUNNER.stop(); UPSTREAM.close(); http.close();
        try (var paths = Files.walk(ROOT)) {for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
    }
    void deleteUser(String id) {
        jdbc.update("DELETE a FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id WHERE r.user_id=?",id);
        for (String table : List.of("ai_grading_binding","ai_grading_task","ai_grading_setting","practice_lifecycle_job","practice_history_annotation","practice_round_question","practice_round",
                "practice_question_state","practice_whiteboard","practice_context","exam_exam","practice_collection","practice_request_receipt","auth_session"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id=?",id);
        jdbc.update("DELETE FROM account_user WHERE id=?",id);
    }


    @ParameterizedTest @ValueSource(strings = {"简答题", "简答题（答题区）"})
    void manualGradeUsesRealPrepareAndReviewAndChargesOnce(String type) throws Exception {
        var sample = submitted(type);
        String target = firstPart(sample);
        var created = ai.grade(user, "bank", sample.collection(), sample.question().questionId(), "grade", target, false);
        assertThat(ai.grade(user, "bank", sample.collection(), sample.question().questionId(), "grade", target, false)).isEqualTo(created);
        var active = ai.grade(user, "bank", sample.collection(), sample.question().questionId(), "force", target, true);
        assertThat(active.path("taskId")).isEqualTo(created.path("taskId"));
        runOne();
        var done = ai.task(user, "bank", sample.collection(), sample.question().questionId(), created.path("taskId").asString());
        assertThat(done.path("status").asString()).isEqualTo("confirmed");
        assertThat(done.path("cost").asLong()).isEqualTo(75);
        assertThat(balances.balance(user).balance()).isEqualTo(9925);
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?", String.class, user)).isEqualTo("ai");
        var confirmed = ai.confirm(user, "bank", sample.collection(), sample.question().questionId(), created.path("taskId").asString(), "confirm", null, null);
        assertThat(confirmed.has("payload")).isTrue();
        assertThat(ai.confirm(user, "bank", sample.collection(), sample.question().questionId(), created.path("taskId").asString(), "confirm", null, null)).isEqualTo(confirmed);
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
        assertThatThrownBy(() -> ai.confirm(user, "bank", sample.collection(), sample.question().questionId(),
                created.path("taskId").asString(), "change-confirmed", null, 1.5))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_CANDIDATE_CONFLICT"));
        var dto = ok(get(aiPath(sample) + "/tasks/" + created.path("taskId").asString()));
        assertThat(dto.toString()).doesNotContain("referenceAnswer", "checkpoint", "inputJson", "keyId", "questionData");
        verify(runner).execute(eq("prepareAiGrading"), any(), anyMap());
        verify(runner).execute(eq("review"), any(), anyMap());
    }

    @Test void checkpointSurvivesCrashAndDoesNotCallOrChargeModelTwice() {
        var sample = submitted("简答题");
        var task = enqueue(sample);
        doAnswer(call -> {
            assertThat(jdbc.update("UPDATE ai_grading_task SET checkpoint=?,status='model_returned' WHERE id=? AND user_id=? AND attempts=?",
                    call.getArgument(3), call.getArgument(0), call.getArgument(1), call.getArgument(2))).isEqualTo(1);
            throw new AssertionError("模拟模型返回并保存检查点后进程退出");
        }).when(aiMapper).checkpoint(anyString(), anyString(), anyInt(), anyString(), any());
        var first = aiJobs.claim().orElseThrow();
        assertThatThrownBy(() -> aiJobs.run(first)).isInstanceOf(AssertionError.class);
        assertThat(balances.balance(user).balance()).isEqualTo(10000);
        assertThat(aiMapper.find(user, task).orElseThrow().checkpoint()).isNotNull();
        reset(aiMapper);
        clock.now = clock.now.plusSeconds(151);
        var takeover = aiJobs.claim().orElseThrow();
        assertThat(takeover.attempts()).isEqualTo(first.attempts() + 1);
        assertThat(aiMapper.checkpoint(first.id(), user, first.attempts(), "{}", clock.instant())).isZero();
        aiJobs.run(takeover);
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("confirmed");
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
        assertThat(balances.balance(user).balance()).isEqualTo(9925);
        aiJobs.run(first);
        assertThat(balances.balance(user).balance()).isEqualTo(9925);
    }

    @Test void malformedOutputGetsExactlyOneRepairAndEachResponseIsBilled() {
        var sample = submitted("简答题");
        String task = enqueue(sample);
        UPSTREAM.malformedReplies = 1;
        runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("queued");
        clock.now = clock.now.plusSeconds(2);
        runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("confirmed");
        assertThat(UPSTREAM.calls(key)).isEqualTo(2);
        assertThat(balances.balance(user).balance()).isEqualTo(9850);
        assertThat(UPSTREAM.requests.getLast().body()).contains("上次输出格式不正确");
    }

    @Test void secondMalformedOutputFailsInsteadOfRepairingAgain() {
        var sample = submitted("简答题");
        String task = enqueue(sample); UPSTREAM.malformedReplies = 10;
        runOne(); clock.now = clock.now.plusSeconds(2); runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("failed");
        assertThat(UPSTREAM.calls(key)).isEqualTo(2);
        assertThat(aiJobs.claim()).isEmpty();
    }

    @Test void networkKeyRetriesAndRepairShareThreeCallBudget() {
        var sample = submitted("简答题");
        jdbc.update("DELETE FROM ai_upstream_key");
        keys.add(user, "sk-fake-500-" + Ids.uuidV7(), "失败 1", 1, 1, null);
        keys.add(user, "sk-fake-500-" + Ids.uuidV7(), "失败 2", 1, 1, null);
        keys.add(user, key, "成功但格式错误", 30000, 90000, null);
        UPSTREAM.malformedReplies = 1;
        String task = enqueue(sample); runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("failed");
        assertThat(aiMapper.find(user, task).orElseThrow().modelCalls()).isEqualTo(3);
        assertThat(UPSTREAM.requests).hasSize(3);
        assertThat(balances.balance(user).balance()).isEqualTo(9925);
    }

    @Test void insufficientBalanceFailsWithoutModelCallOrAutomaticRetry() {
        var sample = submitted("简答题");
        balances.charge(user, 10000, "drain-" + Ids.uuidV7());
        String task = enqueue(sample); runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().errorCode()).isEqualTo("AI_BALANCE_EXHAUSTED");
        assertThat(UPSTREAM.calls(key)).isZero();
        assertThat(aiJobs.claim()).isEmpty();
    }

    @Test void manualReviewDuringModelCallDiscardsAiButChargesReturnedUsage() throws Exception {
        var sample = submitted("简答题");
        String task = enqueue(sample); UPSTREAM.holdGrading = true;
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var claimed = aiJobs.claim().orElseThrow();
            var work = worker.submit(() -> aiJobs.run(claimed));
            assertThat(UPSTREAM.holdArrived.tryAcquire(20, TimeUnit.SECONDS)).isTrue();
            var current = JSON.valueToTree(reads.question(user, sample.collection(), sample.question().questionId(), null));
            lifecycle.review(user, sample.collection(), sample.question().questionId(), "manual", current.path("state").path("revision").asLong(),
                    JSON.valueToTree(Map.of("review", Map.of("score", 0.5, "feedback", "人工决定", "targetId", firstPart(sample)))), null);
            UPSTREAM.holdRelease.countDown(); work.get(30, TimeUnit.SECONDS);
        }
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("superseded");
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?", String.class, user)).isEqualTo("manual");
        assertThat(balances.balance(user).balance()).isEqualTo(9925);
    }

    @Test void queuedTaskIsCancelledByRetryAndOldResultCannotBeConfirmed() {
        var sample = submitted("简答题"); String task = enqueue(sample);
        long revision = jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?", Long.class, user);
        lifecycle.retry(user, sample.collection(), sample.question().questionId(), "redo", revision, JSON.createObjectNode(), null);
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("superseded");
        assertThat(aiJobs.claim()).isEmpty();
        assertThat(UPSTREAM.calls(key)).isZero();
        assertThatThrownBy(() -> ai.confirm(user, "bank", sample.collection(), sample.question().questionId(), task, "late", null, null)).isInstanceOf(ApiException.class);
    }

    @Test void automaticPlanRegistersWithSubmissionAndSupportsAllTargets() {
        aiMapper.setting(user, true, clock.instant());
        var sample = submitted("简答题");
        assertThat(UPSTREAM.calls(key)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=?", Integer.class, user)).isEqualTo(1);
        runOne();
        int count = JSON.readTree(sample.question().data()).path("parts").size();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_binding WHERE user_id=?", Integer.class, user)).isEqualTo(count);
        for (int n = 0; n < count; n++) runOne();
        assertThat(UPSTREAM.calls(key)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=? AND status!='confirmed'", Integer.class, user)).isZero();
    }

    @Test void queuePrefersManualBeforeAutomaticAndSkipsLockedRow() throws Exception {
        aiMapper.setting(user, true, clock.instant());
        var sample = submitted("简答题");
        String manual = enqueue(sample);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                aiMapper.lock(user, manual).orElseThrow();
                locked.countDown();
                try { assertThat(release.await(30, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            try {
                // 被锁定的高优先级行不阻塞另一实例领取其余任务。
                var skipped = aiJobs.claim().orElseThrow();
                assertThat(skipped.id()).isNotEqualTo(manual);
                assertThat(skipped.automatic()).isTrue();
            } finally { release.countDown(); }
            holder.get(30, TimeUnit.SECONDS);
        }
        assertThat(aiJobs.claim().orElseThrow().id()).isEqualTo(manual);
    }

    @Test void requestIdReuseAndIdorDoNotExposePrivateMaterials() throws Exception {
        var sample = submitted("简答题"); String task = enqueue(sample);
        error(post(aiPath(sample) + "/grade", JSON.valueToTree(Map.of("requestId", "grade", "targetId", "another"))), 400, "REQUEST_ID_REUSED");
        var other = auth.register(Ids.uuidV7()+"@example.test", "其他用户", "password123", "test"); viewers.add(other.userId());
        String own = cookie; cookie = "__Host-qf_session=" + other.token();
        try {
            error(get(aiPath(sample) + "/tasks/" + task), 404, "NOT_FOUND");
            error(post(aiPath(sample) + "/grade", JSON.valueToTree(Map.of("requestId", "foreign"))), 404, "NOT_FOUND");
        } finally { cookie = own; }
        var untouched = samples.get("简答题（答题区）");
        error(post(aiPath(untouched) + "/grade", JSON.valueToTree(Map.of("requestId", "before-submit"))), 409, "AI_GRADING_UNAVAILABLE");
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    @Test void extensionReviewOwnsRangeAndStepAndHostStoresItsResult() {
        var sample = submitted("简答题"); String task = enqueue(sample);
        UPSTREAM.gradingText = "{\"score\":9999999.123456,\"feedback\":\"交给拓展处理\"}";
        runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("confirmed");
        var part = JSON.readTree(sample.question().data()).path("parts").get(0);
        var result = JSON.readTree(jdbc.queryForObject("SELECT result FROM practice_question_state WHERE user_id=?", String.class, user));
        assertThat(result.path("parts").path(part.path("id").asString()).path("score").asDouble()).isEqualTo(part.path("maxScore").asDouble());
    }

    @Test void concurrentClicksReuseOneTaskAndOneModelCall() throws Exception {
        var sample = submitted("简答题"); var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> { start.await(); return ai.grade(user,"bank",sample.collection(),sample.question().questionId(),"click-a",firstPart(sample),false); });
            var b = pool.submit(() -> { start.await(); return ai.grade(user,"bank",sample.collection(),sample.question().questionId(),"click-b",firstPart(sample),true); });
            start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).path("taskId")).isEqualTo(b.get(30,TimeUnit.SECONDS).path("taskId"));
        }
        runOne();
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }

    @Test void retryCreatesNewTaskAndCandidateRejectionPreservesOriginalResult() {
        var sample = submitted("简答题"); var before = jdbc.queryForObject("SELECT result FROM practice_question_state WHERE user_id=?",String.class,user);
        String task = enqueue(sample);
        doReturn(cloud.treenewbee.quizforge.rules.RuleResult.rejected("INVALID_ANSWER")).when(runner).execute(eq("review"),any(),anyMap());
        runOne();
        assertThat(aiMapper.find(user,task).orElseThrow().status()).isEqualTo("succeeded");
        assertThat(jdbc.queryForObject("SELECT result FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo(before);
        reset(runner);
        var retry = ai.retry(user,"bank",sample.collection(),sample.question().questionId(),task,"retry-ai");
        assertThat(retry.path("taskId").asString()).isNotEqualTo(task);
        runOne();
        assertThat(UPSTREAM.calls(key)).isEqualTo(2);
    }

    @Test void confirmingRejectedCandidateKeepsSuggestionAndFreezesChosenScore() {
        var sample = submitted("简答题");
        String task = enqueue(sample);
        doReturn(cloud.treenewbee.quizforge.rules.RuleResult.rejected("INVALID_ANSWER"))
                .when(runner).execute(eq("review"), any(), anyMap());
        runOne();
        reset(runner);
        var response = ai.confirm(user, "bank", sample.collection(), sample.question().questionId(), task, "confirm-score", null, 1.5);
        var assessment = response.path("payload").path("state").path("result").path("aiAssessments").path(firstPart(sample));
        assertThat(assessment.path("score").asDouble()).isEqualTo(2);
        assertThat(assessment.path("reviewScore").asDouble()).isEqualTo(1.5);
        assertThat(ai.confirm(user, "bank", sample.collection(), sample.question().questionId(), task, "confirm-score", null, 1.5)).isEqualTo(response);
        assertThatThrownBy(() -> ai.confirm(user, "bank", sample.collection(), sample.question().questionId(), task, "wrong-score", null, null))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_CANDIDATE_CONFLICT"));
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
        verify(runner).execute(eq("review"), any(), anyMap());
    }

    @Test void automaticRegistrationRollsBackTogetherWithSubmission() {
        aiMapper.setting(user,true,clock.instant());
        var sample = samples.get("简答题");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            submits.submit(user,"rollback-submit",sample.collection(),sample.question().questionId(),0,answer("简答题"));
            throw new IllegalStateException("模拟提交事务失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    @Test void browserReportRegistersAutomaticTaskWithoutCallingAiOrPreparingInsideTransaction() {
        var sample = samples.get("简答题");
        aiMapper.setting(user,true,clock.instant());
        var answer = answer("简答题");
        var expected = grader.grade(user,sample.collection(),sample.question().questionId(),answer);
        var locked = service.submit(user,sample.collection(),sample.question().questionId(),"browser-submit",0,answer,null);
        var outcome = JSON.createObjectNode(); outcome.set("result",JSON.readTree(expected.resultJson()));outcome.set("projected",JSON.readTree(expected.projectedJson()));
        var score = outcome.putObject("score").put("score",expected.score()).put("maxScore",expected.maxScore()).put("gradingStatus",expected.gradingStatus());
        if (expected.outlineStatesJson()!=null) score.set("outlineStates",JSON.readTree(expected.outlineStatesJson()));
        clearInvocations(runner);
        service.report(user,sample.collection(),sample.question().questionId(),locked.path("browserGrading").path("generation").asString(),outcome);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(UPSTREAM.calls(key)).isZero();
        verify(runner,never()).execute(eq("prepareAiGrading"),any(),anyMap());
        runOne();runOne();
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"practice","exam"})
    void finishingAndExamHandinScheduleAutomaticAiAndUpdateCompletedHistory(String kind) {
        aiMapper.setting(user,true,clock.instant());
        var sample = samples.get("简答题");
        String scope = sample.collection();
        if (kind.equals("exam")) scope=exams.start(user,sample.collection(),"start-exam",null).path("id").asString();
        drafts.save(user,scope,sample.question().questionId(),"saved",0,answer("简答题"),null);
        if (kind.equals("practice")) {
            var summary = summaries.summary(user,scope);
            jobs.begin(user,scope,"finish",null,summary.path("summaryVersion").asString(),"finish");
        } else exams.submit(user,scope,"handin");
        for (int n=0;n<8 && jobs.tick();n++) { /* 门控推进现有数据库任务，不等待定时器。 */ }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        runOne();runOne();
        assertThat(UPSTREAM.calls(key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("ai");
        var summary = summaries.summary(user,scope);
        assertThat(summary.toString()).contains("2");
        if (kind.equals("exam")) {
            assertThat(jdbc.queryForObject("SELECT status FROM exam_exam WHERE id=?",String.class,scope)).isEqualTo("completed");
            assertThat(jdbc.queryForObject("SELECT payload FROM practice_round_question WHERE user_id=?",String.class,user)).contains("本机假模型反馈");
        }
    }

    @Test void siblingTargetsFinishingTogetherDoNotOverwriteEachOther() throws Exception {
        var original = samples.get("简答题");
        String bank = Ids.uuidV7();
        var source = banks.findById(original.question().bankId()).orElseThrow();
        banks.insert(new cloud.treenewbee.quizforge.content.infrastructure.BankRow(bank,
                cloud.treenewbee.quizforge.practice.ai.PracticeAiAccess.hash(bank), source.bankUuid(),
                source.sourceTitle(), source.description(), source.formatVersion(), source.features(),
                source.listening(), 1, clock.instant()));
        extraBanks.add(bank);
        var data = (tools.jackson.databind.node.ObjectNode) JSON.readTree(original.question().data());
        var parts = (tools.jackson.databind.node.ArrayNode) data.path("parts");
        var second = parts.get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) second).put("id", "sibling");
        parts.add(second);
        var q = original.question();
        questions.insert(new QuestionRow(bank, q.questionId(), 0, q.title(), q.extensionDigest(),
                null, q.listening(), JSON.writeValueAsString(data), q.maxScore().multiply(java.math.BigDecimal.valueOf(2)),
                cloud.treenewbee.quizforge.practice.ai.PracticeAiAccess.hash(data.toString()), null, null));
        jdbc.update("UPDATE practice_collection SET bank_id=? WHERE id=? AND user_id=?", bank, original.collection(), user);
        var sample = new Sample(original.collection(), questions.findByPosition(bank, 0).orElseThrow());
        samples.put("简答题", sample);
        aiMapper.setting(user, true, clock.instant());
        submitted("简答题");
        runOne(); // 生成两个独立小题任务。
        var firstTask = aiJobs.claim().orElseThrow();
        var secondTask = aiJobs.claim().orElseThrow();
        var arrived = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                arrived.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(runner).execute(eq("review"), any(), anyMap());
        int generation = jdbc.queryForObject("SELECT grading_generation FROM practice_question_state WHERE user_id=?", Integer.class, user);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> aiJobs.run(firstTask));
            var b = pool.submit(() -> aiJobs.run(secondTask));
            try { assertThat(arrived.await(30, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }
        // 同 revision 的第二个 review 只重算采用，不重复请求模型。
        while (aiJobs.claim().map(task -> { aiJobs.run(task); return true; }).orElse(false)) { }
        var result = JSON.readTree(jdbc.queryForObject("SELECT result FROM practice_question_state WHERE user_id=?", String.class, user));
        assertThat(result.path("aiAssessments").size()).isEqualTo(2);
        assertThat(result.path("score").asDouble()).isEqualTo(4);
        assertThat(UPSTREAM.calls(key)).isEqualTo(2);
        assertThat(balances.balance(user).balance()).isEqualTo(9850);
        assertThat(jdbc.queryForObject("SELECT grading_generation FROM practice_question_state WHERE user_id=?", Integer.class, user)).isEqualTo(generation);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=? AND status!='confirmed'", Integer.class, user)).isZero();
    }

    /**
     * 题库广场 3/4：模型调用期间完成练习并“再练一次”，归档事务清空作答并切换到新版本（PracticeUpdates.afterRestart）。
     * 旧任务回来时题目已属于新版本、作答已清空，不得把旧版本题目的 AI 结果写到新版本题目上。
     */
    @Test void restartSwitchingToNewVersionDiscardsRunningAiTaskOfOldVersion() throws Exception {
        var original = samples.get("简答题");
        var q = original.question();
        var source = banks.findById(q.bankId()).orElseThrow();
        String v2 = Ids.uuidV7(), pack = Ids.uuidV7();
        banks.insert(new cloud.treenewbee.quizforge.content.infrastructure.BankRow(v2,
                cloud.treenewbee.quizforge.practice.ai.PracticeAiAccess.hash(v2), source.bankUuid(),
                source.sourceTitle(), source.description(), source.formatVersion(), source.features(),
                source.listening(), 1, clock.instant()));
        extraBanks.add(v2);
        var data = (ObjectNode) JSON.readTree(q.data());
        ((ObjectNode) data.path("parts").get(0)).set("stem",
                JSON.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"第二版题干\"}]}]}"));
        questions.insert(new QuestionRow(v2, q.questionId(), 0, q.title() + "（第二版）", q.extensionDigest(),
                null, q.listening(), JSON.writeValueAsString(data), q.maxScore(),
                cloud.treenewbee.quizforge.practice.ai.PracticeAiAccess.hash(data.toString()), null, null));
        jdbc.update("""
                UPDATE practice_collection SET source_pack_id=?, source_bank_uuid=?, source_revision=1, update_status='current'
                 WHERE id=? AND user_id=?""", pack, source.bankUuid(), original.collection(), user);
        doReturn(Map.of(pack, new PublishedVersions.Latest(false, 2, Map.of(source.bankUuid(), v2))))
                .when(versions).latest(org.mockito.ArgumentMatchers.<Collection<String>>argThat(ids -> ids != null && ids.contains(pack)), anyCollection());

        var sample = submitted("简答题");
        String task = enqueue(sample); UPSTREAM.holdGrading = true;
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var claimed = aiJobs.claim().orElseThrow();
            var work = worker.submit(() -> aiJobs.run(claimed));
            assertThat(UPSTREAM.holdArrived.tryAcquire(20, TimeUnit.SECONDS)).isTrue();
            var finishing = summaries.summary(user, sample.collection());
            jobs.begin(user, sample.collection(), "finish-v1", finishing.path("roundId").asString(),
                    finishing.path("summaryVersion").asString(), "finish");
            for (int n = 0; n < 8 && jobs.tick(); n++) { /* 门控推进完成任务 */ }
            var finished = summaries.summary(user, sample.collection());
            assertThat(finished.path("canRestart").asBoolean()).as(finished.toString()).isTrue();
            jobs.begin(user, sample.collection(), "restart-v2", finished.path("roundId").asString(),
                    finished.path("summaryVersion").asString(), "archive");
            for (int n = 0; n < 8 && jobs.tick(); n++) { /* 门控推进归档任务 */ }
            assertThat(jdbc.queryForObject("SELECT bank_id FROM practice_collection WHERE id=?", String.class, sample.collection())).isEqualTo(v2);
            UPSTREAM.holdRelease.countDown(); work.get(30, TimeUnit.SECONDS);
        }
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("superseded");
        // 新一轮中本题没有任何作答，更没有来自旧任务的 AI 结果。
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM practice_question_state WHERE user_id=? AND question_id=?
                   AND (status='submitted' OR grading_source='ai' OR result IS NOT NULL)""",
                Integer.class, user, q.questionId())).isZero();
        var current = JSON.valueToTree(reads.question(user, sample.collection(), q.questionId(), null));
        assertThat(current.toString()).contains("第二版题干").doesNotContain("本机假模型反馈");
        assertThat(aiJobs.claim()).isEmpty();
    }

    @Test void changedQuestionContentInvalidatesTaskBeforePreparingMaterials() {
        var sample = submitted("简答题");
        String task = enqueue(sample);
        // 不改共享内容：模拟实例更新后，本题状态仍带旧内容指纹。
        jdbc.update("UPDATE practice_question_state SET content_sha256=? WHERE user_id=?", new byte[32], user);
        runOne();
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("superseded");
        assertThat(UPSTREAM.calls(key)).isZero();
        verify(runner, never()).execute(eq("prepareAiGrading"), any(), anyMap());
    }

    @Test void deletingCollectionCancelsQueuedAiWithoutCallingModel() {
        var sample = submitted("简答题");
        String task = enqueue(sample);
        privateCollections.delete(user, sample.collection());
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("superseded");
        assertThat(aiJobs.claim()).isEmpty();
        assertThat(UPSTREAM.calls(key)).isZero();
        assertThatThrownBy(() -> ai.task(user, "bank", sample.collection(), sample.question().questionId(), task))
                .isInstanceOf(ApiException.class);
    }

    @Test void deletingGradingExamCancelsItsAiTask() {
        var sample = samples.get("简答题");
        String exam = exams.start(user, sample.collection(), "start", null).path("id").asString();
        drafts.save(user, exam, sample.question().questionId(), "save-exam", 0, answer("简答题"), null);
        exams.submit(user, exam, "handin");
        // 在归档前门控后台任务，构造已经判完本题、整卷仍处于 grading 的状态。
        jobs.tick();
        String task = ai.grade(user, "exam", exam, sample.question().questionId(), "exam-ai", firstPart(sample), false)
                .path("taskId").asString();
        exams.delete(user, exam);
        assertThat(aiMapper.find(user, task).orElseThrow().status()).isEqualTo("superseded");
        assertThat(aiJobs.claim()).isEmpty();
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    @Test void oversizedHttpRequestIsRejectedBeforeCreatingTask() throws Exception {
        var sample = submitted("简答题");
        error(post(aiPath(sample) + "/grade", JSON.valueToTree(Map.of("requestId", "x".repeat(9000)))), 413, "AI_REQUEST_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_task WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(UPSTREAM.calls(key)).isZero();
    }

    Sample submitted(String type) {
        var sample = samples.get(type);
        submits.submit(user, "submit-" + Ids.uuidV7(), sample.collection(), sample.question().questionId(), 0, answer(type));
        clearInvocations(runner);
        return sample;
    }
    String firstPart(Sample sample) { return JSON.readTree(sample.question().data()).path("parts").get(0).path("id").asString(); }
    String enqueue(Sample sample) { return ai.grade(user, "bank", sample.collection(), sample.question().questionId(), "grade", firstPart(sample), false).path("taskId").asString(); }
    void runOne() { aiJobs.run(aiJobs.claim().orElseThrow()); }
    String aiPath(Sample sample) { return base(sample) + "/questions/" + sample.question().questionId() + "/ai"; }
    String base(Sample sample) {return "/api/collections/bank/"+sample.collection();}
    HttpResponse<String> get(String path) throws Exception {return send(path,null);}
    HttpResponse<String> post(String path,JsonNode body) throws Exception {return send(path,body);}
    HttpResponse<String> send(String path,JsonNode body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(60))
                .header("Cookie",cookie).header("Origin","http://browser.example.test").header("Content-Type","application/json");
        if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> response) {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return JSON.readTree(response.body());}
    void error(HttpResponse<String> response,int status,String code) {assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo(code);}
    JsonNode answer(String type) {
        var data=JSON.readTree(samples.get(type).question().data());
        if(type.equals("单选题"))return JSON.valueToTree(Map.of("selectedOptionId",data.path("correctOptionId").asString()));
        if(type.equals("多选题"))return JSON.valueToTree(Map.of("selectedOptionIds",data.path("correctOptionIds")));
        if(type.equals("判断题"))return JSON.valueToTree(Map.of("value",data.path("correctAnswer").asBoolean()));
        var responses=JSON.createObjectNode();
        for(var part:data.path("parts")) {
            String id=part.path("id").asString();
            responses.set(id,type.equals("简答题")?JSON.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"缓存减少计算\"}]}]}"):
                    JSON.valueToTree(Map.of("type","qf-answer-area","formatVersion",1,"id","response-"+id,"width",760,"height",part.path("answerHeight").asInt(480),"strokes",List.of(),"images",List.of(),"text","缓存减少计算")));
        }
        return JSON.createObjectNode().set("responses",responses);
    }
    static Path temp() {try{return Files.createTempDirectory("qf-ai-grading-");}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    static Path dir(String name) {try{return Files.createDirectory(ROOT.resolve(name));}catch(Exception error){throw new ExceptionInInitializerError(error);}}
}
