package cloud.treenewbee.quizforge.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
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
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.infrastructure.*;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.*;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import cloud.treenewbee.quizforge.rules.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(120)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ExamModeIntegrationTest.TimeConfig.class)
class ExamModeIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final String TOKEN = "exam-mode-test";
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
        .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
        .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
        .withEnv("QF_RULE_WORKER_LIMIT", "8").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
        .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN))
        .withStartupTimeout(Duration.ofMinutes(2));
    static {
        RUNNER.start();
    }
    static class MutableClock extends Clock {
        volatile Instant value = Instant.parse("2026-11-02T12:00:00Z");

        public Instant instant() {
            return value;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock examClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("qf.practice.jobs.enabled", () -> false);
        r.add("qf.security.allowed-origin", () -> "https://exam.example.test");
        r.add("qf.storage.blob-dir", BLOBS::toString);
        r.add("qf.import.temp-dir", UPLOADS::toString);
        r.add("qf.rule-runner.base-url", () -> "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090));
        r.add("qf.rule-runner.token", () -> TOKEN);
        r.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired AuthService auth;
    @Autowired ImportService imports;

    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;

    @Autowired ExamService exams;
    @Autowired ExamDeadlineScanner scanner;

    @Autowired PracticeLifecycleJobs jobs;
    @Autowired MutableClock clock;

    @Autowired QuestionMapper questions;
    @Autowired cloud.treenewbee.quizforge.quota.application.PrivateCollectionService deletion;

    @MockitoSpyBean SubmitGrader grader;
    @MockitoSpyBean RuleRunnerClient runner;

    @MockitoSpyBean PracticeReadMapper readMapper;
    @MockitoSpyBean cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper contexts;

    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();
    record Sample(String collection, QuestionRow question) {
    }
    final Map<String, Sample> seeds = new HashMap<>(), samples = new HashMap<>();
    String seedUser, user, cookie;

    @BeforeAll void seed() throws Exception {
        jdbc.update("UPDATE auth_policy SET login_code=FALSE,registration_code=FALSE,registration_captcha=FALSE,invitation_required=FALSE WHERE id=1");
        seedUser = auth.register(Ids.uuidV7() + "@example.test", "考试种子", "password-123", "test").userId();
        for (var collection:imports.importArchive(seedUser, "basic-types.qbank", Files.newInputStream(Path.of("../samples/basic-types.qbank"))))
        seeds.put(collection.title(), new Sample(collection.id(), questions.findByPosition(collection.bankId(), 0).orElseThrow()));
        jdbc.update("INSERT INTO admin_extension_review(digest,extension_id,version,status) SELECT digest,extension_id,version,'approved' FROM content_extension_package ON DUPLICATE KEY UPDATE status='approved'");
    }

    @BeforeEach void viewer() {
        reset(grader, runner, readMapper, contexts);
        clock.value = Instant.parse("2026-11-02T12:00:00Z");
        var registered = auth.register(Ids.uuidV7() + "@example.test", "考试测试", "password-123", "test");
        user = registered.userId();
        cookie = "__Host-qf_session=" + registered.token();
        samples.clear();
        seeds.forEach((type, seed) -> {
            String id = Ids.uuidV7();
            jdbc.update("INSERT INTO practice_collection(id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?", id, user, seed.collection());
            samples.put(type, new Sample(id, seed.question()));
        });
    }

    @AfterEach void cleanupViewer() {
        reset(grader, runner, readMapper, contexts);
        deleteUser(user);
    }

    @AfterAll void cleanup() throws Exception {
        deleteUser(seedUser);
        RUNNER.stop();
        http.close();
        try (var files = Files.walk(ROOT)) {
            for (Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
        }
    }

    private void deleteUser(String id) {
        jdbc.update("DELETE a FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id WHERE r.user_id=?", id);
        for (String table:List.of("exam_exam", "practice_lifecycle_job", "practice_history_annotation", "practice_user_resource", "practice_whiteboard", "practice_request_receipt", "practice_round_question", "practice_round", "practice_question_state", "practice_context", "practice_collection", "auth_session"))
        jdbc.update("DELETE FROM " + table + " WHERE user_id=?", id);
        jdbc.update("DELETE FROM account_user WHERE id=?", id);
    }

    @Test void creationIsIdempotentAndDeletedCreationCannotReplay() throws Exception {
        var first = start("判断题", "create", 1);
        String id = first.path("id").asString();
        assertThat(start("判断题", "create", 1).path("id").asString()).isEqualTo(id);
        assertThat(first.path("deadline").asString()).isEqualTo(clock.instant().plusSeconds(60).toString());
        assertThat(count("exam_exam")).isEqualTo(1);
        error(send("POST", bank("判断题") + "/exams", Map.of("requestId", "create", "durationMinutes", 2)), 400, "REQUEST_ID_REUSED");
        assertThat(ok(send("DELETE", exam(id) + "/exam", null)).path("deleted").asString()).isEqualTo(id);
        ok(send("DELETE", exam(id) + "/exam", null));
        error(send("POST", bank("判断题") + "/exams", Map.of("requestId", "create", "durationMinutes", 1)), 409, "EXAM_DELETED");
        error(send("GET", exam(id), null), 404, "NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(ints = {
        0, -1, 1441
    })
    void rejectsInvalidDuration(int minutes) throws Exception {
        error(send("POST", bank("判断题") + "/exams", Map.of("requestId", "invalid", "durationMinutes", minutes)), 400, "VALIDATION_FAILED");
        assertThat(count("exam_exam")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {
        "1.5", "true", "\"10\""
    })
    void durationMustBeAnIntegerOrNull(String value) throws Exception {
        error(send("POST", bank("判断题") + "/exams", JSON.readTree("{\"requestId\":\"bad-time\",\"durationMinutes\":" + value + "}")), 400, "VALIDATION_FAILED");
    }

    @Test void disabledFeatureRejectsStart() throws Exception {
        String bank = samples.get("判断题").question().bankId();
        jdbc.update("UPDATE content_bank SET features=JSON_SET(features,'$.exam',false) WHERE id=?", bank);
        try {
            error(send("POST", this.bank("判断题") + "/exams", Map.of("requestId", "disabled")), 403, "FEATURE_DISABLED");
        }
        finally {
            jdbc.update("UPDATE content_bank SET features=JSON_SET(features,'$.exam',true) WHERE id=?", bank);
        }
    }

    @Test void examDraftAndWhiteboardAreIsolatedAndForbiddenActionsCannotBypassBankRoute() throws Exception {
        String id = start("判断题", "start", null).path("id").asString();
        String path = question(id, "判断题");
        var payload = ok(send("GET", path, null));
        assertThat(payload.path("capabilities").path("canSave").asBoolean()).isTrue();
        assertThat(payload.path("capabilities").path("canSubmit").asBoolean()).isFalse();
        assertThat(payload.toString()).doesNotContain("correctAnswer");
        ok(action(id, "判断题", "draft", 0, "save", Map.of("answer", answer("判断题", true))));
        ok(action(id, "判断题", "whiteboard", 0, "board", Map.of("draft", board("exam"))));
        var regular = ok(send("GET", bank("判断题") + "/questions/" + qid("判断题"), null));
        assertThat(regular.path("state").path("revision").asInt()).isZero();
        assertThat(regular.path("whiteboard").path("draft").path("strokes")).isEmpty();
        for (String action:List.of("submit", "retry", "review", "ai"))
        error(action(id, "判断题", action, 1, action, Map.of("answer", answer("判断题", true))), 409, "EXAM_ACTION_FORBIDDEN");
        error(send("POST", question(id, "判断题") + "/ai/grade", Map.of()), 409, "EXAM_ACTION_FORBIDDEN");
        error(send("POST", "/api/collections/bank/" + id + "/questions/" + qid("判断题") + "/actions", Map.of("action", "submit", "requestId", "bypass", "revision", 1, "data", Map.of("answer", answer("判断题", true)))), 404, "NOT_FOUND");
        error(send("GET", "/api/collections/bank/" + id + "/summary", null), 404, "NOT_FOUND");
        verifyNoInteractions(grader);
    }

    @Test void concurrentCreationWithSameRequestCreatesOneContext() throws Exception {
        String collection = samples.get("判断题").collection();
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);return exams.start(user, collection, "create-race", 1);
            });
            var b = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);return exams.start(user, collection, "create-race", 1);
            });
            assertThat(a.get(20, TimeUnit.SECONDS).path("id")).isEqualTo(b.get(20, TimeUnit.SECONDS).path("id"));
        }
        assertThat(count("exam_exam")).isEqualTo(1);
        assertThat(count("practice_context")).isEqualTo(1);
    }

    @Test void deadlineIsRecheckedAfterWaitingForContextLock() throws Exception {
        String id = start("判断题", "start", 1).path("id").asString(), context = exams.owned(user, id).contextId();
        var arrived = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var request = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
            transaction.executeWithoutResult(status -> {
                contexts.lockForUpdate(context, user).orElseThrow();
                doAnswer(call -> {
                    arrived.countDown();
                    return sql.getMapper(cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper.class).lockForUpdate(context, user);
                }).when(contexts).lockForUpdate(context, user);
                request.set(pool.submit(() -> action(id, "判断题", "draft", 0, "waiting", Map.of("answer", answer("判断题", true)))));
                try {
                    assertThat(arrived.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    throw new IllegalStateException(error);
                }
                clock.value = clock.instant().plusSeconds(60);
            });
            error(request.get().get(30, TimeUnit.SECONDS), 409, "EXAM_DEADLINE_PASSED");
        }
        assertThat(count("practice_question_state")).isZero();
        scanner.scan();
        complete(id);
    }

    @Test void deadlineIsServerControlledAndWritesAreRejectedAfterRefresh() throws Exception {
        String id = start("判断题", "start", 1).path("id").asString();
        clock.value = clock.instant().plusSeconds(60);
        error(action(id, "判断题", "draft", 0, "late", Map.of("answer", answer("判断题", true))), 409, "EXAM_DEADLINE_PASSED");
        error(action(id, "判断题", "whiteboard", 0, "late-board", Map.of("draft", board("late"))), 409, "EXAM_DEADLINE_PASSED");
        assertThat(ok(send("GET", exam(id), null)).path("exam").path("deadline").asString()).isEqualTo(clock.instant().toString());
        assertThat(count("practice_question_state")).isZero();
        assertThat(count("practice_whiteboard")).isZero();
    }

    @Test void automaticAndUserSubmissionRaceCreateOnlyOneJobAndRound() throws Exception {
        String id = start("判断题", "start", 1).path("id").asString();
        ok(action(id, "判断题", "draft", 0, "save", Map.of("answer", answer("判断题", true))));
        clock.value = clock.instant().plusSeconds(60);
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var manual = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);return exams.submit(user, id, "submit");
            });
            var automatic = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);scanner.scan();return true;
            });
            manual.get(20, TimeUnit.SECONDS);
            assertThat(automatic.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("practice_lifecycle_job")).isEqualTo(1);
        assertThat(count("practice_round")).isEqualTo(1);
        complete(id);
        assertThat(count("practice_round")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {
        "单选题", "多选题", "判断题", "简答题", "简答题（答题区）"
    })
    void realGradingAndHistoryRetainExtensionSemantics(String type) throws Exception {
        String id = start(type, "start", null).path("id").asString();
        ok(action(id, type, "draft", 0, "save", Map.of("answer", answer(type, true))));
        var first = ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")));
        assertThat(ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")))).isEqualTo(first);
        complete(id);
        verify(grader, times(1)).grade(eq(user), eq(id), eq(qid(type)), any());
        var current = ok(send("GET", question(id, type), null));
        assertThat(current.path("state").path("result").path("gradingStatus").asString()).isEqualTo(type.equals("简答题（答题区）") ? "pending":"graded");
        var records = ok(send("GET", bank(type) + "/history", null)).path("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).path("mode").asString()).isEqualTo("exam");
        assertThat(records.get(0).has("questions")).isFalse();
        var history = ok(send("GET", bank(type) + "/history/" + records.get(0).path("id").asString(), null));
        assertThat(history.path("mode").asString()).isEqualTo("exam");
        assertThat(history.path("snapshotVersion").asInt()).isEqualTo(1);
        assertThat(history.path("questions")).hasSize(1);
        assertThat(history.path("questions").get(0).path("payload").path("state").path("answer")).isEqualTo(answer(type, true));
        error(send("DELETE", exam(id) + "/exam", null), 409, "EXAM_COMPLETED");
        assertThat(ok(send("GET", bank(type) + "/exams", null)).path("records")).isEmpty();
    }

    @Test void unansweredExamCompletesWithZeroAndNeverInvokesGrader() throws Exception {
        String id = start("判断题", "start", null).path("id").asString();
        ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")));
        complete(id);
        var summary = ok(send("GET", exam(id) + "/summary", null));
        assertThat(summary.path("score").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.path("maxScore").decimalValue()).isPositive();
        verifyNoInteractions(grader);
    }

    @Test void gradingFailurePreservesLockedAnswersAndCanRetry () throws Exception {
        String id = start("判断题", "start", null).path("id").asString();
        ok(action(id, "判断题", "draft", 0, "save", Map.of("answer", answer("判断题", true))));
        doThrow(new RuleRunnerException("故障")).when(grader).grade(eq(user), eq(id), anyString(), any());
        ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")));
        assertThat(jobs.tick()).isTrue();
        assertThat(exams.status(user, id).path("gradingErrors")).hasSize(1);
        error(action(id, "判断题", "draft", 1, "locked", Map.of("answer", answer("判断题", false))), 409, "EXAM_LOCKED");
        assertThat(ok(send("GET", question(id, "判断题"), null)).path("state").path("answer")).isEqualTo(answer("判断题", true));
        reset(grader);
        ok(send("POST", exam(id) + "/exam", Map.of("requestId", "retry-grading")));
        complete(id);
        assertThat(count("practice_round")).isEqualTo(1);
    }

    @Test void completedExamManualReviewUpdatesHistoryInSameTransaction() throws Exception {
        String type = "简答题（答题区）", id = start(type, "start", null).path("id").asString();
        ok(action(id, type, "draft", 0, "save", Map.of("answer", answer(type, true))));
        ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")));
        complete(id);
        var payload = ok(send("GET", question(id, type), null));
        assertThat(payload.path("capabilities").path("canReview").asBoolean()).isTrue();
        String part = JSON.readTree(samples.get(type).question().data()).path("parts").get(0).path("id").asString();
        var response = ok(action(id, type, "review", payload.path("state").path("revision").asLong(), "review", Map.of("review", Map.of("score", 1.5, "targetId", part))));
        assertThat(response.path("state").path("result").path("score").decimalValue()).isEqualByComparingTo("1.5");
        String round = exams.owned(user, id).roundId();
        var history = ok(send("GET", bank(type) + "/history/" + round, null));
        assertThat(history.path("questions").get(0).path("payload").path("state").path("result").path("score").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(history.path("summary").path("score").decimalValue()).isEqualByComparingTo("1.5");
    }

    @Test void frozenBankSurvivesCollectionVersionChange() throws Exception {
        String id = start("判断题", "start", null).path("id").asString();
        String collection = samples.get("判断题").collection();
        jdbc.update("UPDATE practice_collection SET bank_id=? WHERE id=?", samples.get("单选题").question().bankId(), collection);
        var overview = ok(send("GET", exam(id), null));
        assertThat(overview.path("questions").get(0).path("id").asString()).isEqualTo(qid("判断题"));
        ok(send("GET", question(id, "判断题") + "/page", null));
        ok(action(id, "判断题", "draft", 0, "save", Map.of("answer", answer("判断题", true))));
        ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")));
        complete(id);
        assertThat(ok(send("GET", exam(id) + "/summary", null)).path("score").decimalValue()).isEqualByComparingTo(samples.get("判断题").question().maxScore());
    }

    @Test void deletedInFlightExamDiscardsGradeAndCollectionDeletionCascades() throws Exception {
        String id = start("判断题", "start", null).path("id").asString();
        ok(action(id, "判断题", "draft", 0, "save", Map.of("answer", answer("判断题", true))));
        ok(send("POST", exam(id) + "/exam", Map.of("requestId", "submit")));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();return call.callRealMethod();
        }).when(grader).grade(eq(user), eq(id), anyString(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var work = pool.submit(jobs::tick);
            try {
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                ok(send("DELETE", exam(id) + "/exam", null));
            }
            finally {
                release.countDown();
            }
            work.get(30, TimeUnit.SECONDS);
        }
        assertThat(count("practice_question_state")).isZero();
        assertThat(count("practice_round")).isZero();
        String other = start("判断题", "other", null).path("id").asString();
        deletion.delete(user, samples.get("判断题").collection());
        error(send("GET", exam(other), null), 404, "NOT_FOUND");
        assertThat(count("exam_exam")).isZero();
    }

    @Test void everyExamRouteRejectsOtherUsers() throws Exception {
        String id = start("判断题", "start", 1).path("id").asString();
        var other = auth.register(Ids.uuidV7() + "@example.test", "其他用户", "password-123", "test");
        String mine = cookie;
        cookie = "__Host-qf_session=" + other.token();
        try {
            for (String path:List.of(exam(id), exam(id) + "/exam", question(id, "判断题"), question(id, "判断题") + "/page", exam(id) + "/summary"))
            error(send("GET", path, null), 404, "NOT_FOUND");
            error(send("POST", exam(id) + "/exam", Map.of("requestId", "idor")), 404, "NOT_FOUND");
            error(send("DELETE", exam(id) + "/exam", null), 404, "NOT_FOUND");
            error(action(id, "判断题", "draft", 0, "idor-draft", Map.of("answer", answer("判断题", true))), 404, "NOT_FOUND");
        }
        finally {
            cookie = mine;
            deleteUser(other.userId());
        }
    }

    @Test void draftAndWhiteboardNeverEnumerateBankOrCallRules() throws Exception {
        String id = start("判断题", "start", 1).path("id").asString();
        clearInvocations(readMapper, runner);
        ok(action(id, "判断题", "draft", 0, "save", Map.of("answer", answer("判断题", true))));
        ok(action(id, "判断题", "whiteboard", 0, "board", Map.of("draft", board("one"))));
        verify(readMapper, never()).questions(anyString(), anyString(), anyString());
        verifyNoInteractions(runner);
    }

    @Test void expiryScanUsesDeadlineIndex() {
        var plan = jdbc.queryForMap("EXPLAIN SELECT id,user_id FROM exam_exam FORCE INDEX(idx_exam_deadline) WHERE status='in_progress' AND deadline_at <= ? ORDER BY deadline_at,id LIMIT 100", java.sql.Timestamp.from(clock.instant()));
        assertThat(plan.get("key")).isEqualTo("idx_exam_deadline");
        assertThat(plan.get("type")).isIn("range", "ref");
    }
    /** 显式启用的本机探针，避免 CI 共享主机的抖动被误当成性能结论。 */

    @Test
    @Timeout(300)
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "qf.exam.measure", matches = "true")
    void localLatencyProbe() throws Exception {
        var collection = imports.importArchive(user, "loadtest-300.qbank", Files.newInputStream(Path.of("../samples/loadtest-300.qbank"))).get(0);
        var question = questions.findByPosition(collection.bankId(), 0).orElseThrow();
        String bankPath = "/api/collections/bank/" + collection.id();
        String examId = ok(send("POST", bankPath + "/exams", Map.of("requestId", "probe-start"))).path("id").asString();
        String[] paths = {
            bankPath + "/questions/" + question.questionId() + "/actions", exam(examId) + "/questions/" + question.questionId() + "/actions"
        };
        var answer = Map.of("selectedOptionId", JSON.readTree(question.data()).path("correctOptionId").asString());
        var timings = new java.util.TreeMap<String, Double>();
        clearInvocations(readMapper, runner);
        for (String action:List.of("draft", "whiteboard")) {
            var normal = new java.util.ArrayList<Long>();
            var exam = new java.util.ArrayList<Long>();
            for (int n = 0;n<120;n++)for (int mode = 0;mode<2;mode++) {
                Object data = action.equals("draft") ? Map.of("answer", answer):Map.of("draft", board("stroke-" + n));
                long start = System.nanoTime();
                ok(send("POST", paths[mode], Map.of("requestId", action + "-" + mode + "-" + n, "revision", n, "action", action, "data", data)));
                long elapsed = System.nanoTime()-start;
                if (n >= 20)(mode == 0 ? normal:exam).add(elapsed);
            }
            timings.put(action + ".practice.p95_ms", p95(normal));
            timings.put(action + ".exam.p95_ms", p95(exam));
        }
        verify(readMapper, never()).questions(anyString(), anyString(), anyString());
        verifyNoInteractions(runner);
        // 同一已冻结试卷准备 300 个非空草稿，使后台评分覆盖整个前台采样时段。
        var context = exams.owned(user, examId).contextId();
        for (var row:jdbc.queryForList("SELECT question_id,data,content_sha256 FROM content_question WHERE bank_id=? ORDER BY position", collection.bankId())) {
            var data = JSON.readTree((String)row.get("data"));
            JsonNode saved;
            if (data.has("correctOptionId"))saved = JSON.valueToTree(Map.of("selectedOptionId", data.get("correctOptionId")));
            else if (data.has("correctOptionIds"))saved = JSON.valueToTree(Map.of("selectedOptionIds", data.get("correctOptionIds")));
            else saved = JSON.valueToTree(Map.of("value", data.get("correctAnswer")));
            jdbc.update("INSERT IGNORE INTO practice_question_state(context_id,question_id,user_id,status,answer,revision,updated_at,content_sha256) VALUES(?,?,?,'draft',?,1,?,?)", context, row.get("question_id"), user, JSON.writeValueAsString(saved), java.sql.Timestamp.from(clock.instant()), row.get("content_sha256"));
        }
        var other = auth.register(Ids.uuidV7() + "@example.test", "前台用户", "password-123", "probe");
        String foreground = Ids.uuidV7(), originalCookie = cookie;
        jdbc.update("INSERT INTO practice_collection(id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?", foreground, other.userId(), samples.get("判断题").collection());
        String path = "/api/collections/bank/" + foreground + "/questions/" + qid("判断题");
        cookie = "__Host-qf_session=" + other.token();
        try {
            probeSubmissions(path, 10);
            timings.put("submit.idle.p95_ms", p95(probeSubmissions(path, 60)));
            exams.submit(user, examId, "probe-submit");
            var entered = new CountDownLatch(1);
            doAnswer(call -> {
                entered.countDown();return call.callRealMethod();
            }).when(grader).grade(eq(user), eq(examId), anyString(), any());
            var worker = Executors.newSingleThreadScheduledExecutor();
            var scheduled = worker.scheduleWithFixedDelay(jobs::tick, 0, 250, TimeUnit.MILLISECONDS);
            try {
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                timings.put("submit.background.p95_ms", p95(probeSubmissions(path, 60)));
                assertThat(exams.owned(user, examId).status()).isEqualTo("grading");
            }
            finally {
                scheduled.cancel(false);
                worker.shutdown();
                assertThat(worker.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
        finally {
            cookie = originalCookie;
            deleteUser(other.userId());
        }
        var metrics = http.send(HttpRequest.newBuilder(URI.create("http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090) + "/v1/metrics/prometheus")).header("X-Internal-Token", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(200);
        Files.createDirectories(Path.of("../.artifacts/local/exam"));
        Files.writeString(Path.of("../.artifacts/local/exam/runner-metrics.txt"), metrics.body());
        var output = Path.of("../.artifacts/local/exam/p95.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(timings));
        System.out.println("EXAM_LOCAL_P95 " + JSON.writeValueAsString(timings));
    }

    private List<Long> probeSubmissions(String path, int count) throws Exception {
        var timings = new java.util.ArrayList<Long>();
        for (int i = 0;i<count;i++) {
            long revision = ok(send("GET", path, null)).path("state").path("revision").asLong();
            long start = System.nanoTime();
            var saved = ok(send("POST", path + "/actions", Map.of("requestId", Ids.uuidV7(), "revision", revision, "action", "submit", "data", Map.of("answer", answer("判断题", true)))));
            timings.add(System.nanoTime()-start);
            ok(send("POST", path + "/actions", Map.of("requestId", Ids.uuidV7(), "revision", saved.path("revision").asLong(), "action", "retry", "data", Map.of())));
        }
        return timings;
    }

    private static double p95(List<Long> values) {
        var sorted = values.stream().sorted().toList();
        return sorted.get((int)Math.ceil(sorted.size() * .95)-1)/1_000_000.0;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, user);
    }

    private String qid(String type) {
        return samples.get(type).question().questionId();
    }

    private String bank(String type) {
        return "/api/collections/bank/" + samples.get(type).collection();
    }

    private String exam(String id) {
        return "/api/collections/exam/" + id;
    }

    private String question(String id, String type) {
        return exam(id) + "/questions/" + qid(type);
    }

    private JsonNode start(String type, String request, Integer minutes) throws Exception {
        var body = JSON.createObjectNode().put("requestId", request).put("durationMinutes", minutes);
        return ok(send("POST", bank(type) + "/exams", body));
    }

    private void complete(String id) {
        for (int i = 0;i<20;i++) {
            if (exams.owned(user, id).status().equals("completed"))return;
            assertThat(jobs.tick()).isTrue();
            assertThat(exams.status(user, id).path("gradingErrors")).as(exams.status(user, id).toString()).isEmpty();
        }
        throw new AssertionError("考试任务未完成");
    }

    private HttpResponse<String> action(String id, String type, String action, long revision, String request, Object data) throws Exception {
        return send("POST", question(id, type) + "/actions", Map.of("requestId", request, "action", action, "revision", revision, "data", data));
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(90))
            .header("Cookie", cookie).header("Origin", "https://exam.example.test").header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
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
                if (!isCorrect) {
                    wrong.add(option.path("id").asString());
                    break;
                }
            }
            if (wrong.isEmpty()) throw new AssertionError("The sample must have a valid but incorrect option");
            return JSON.valueToTree(Map.of("selectedOptionIds", correct ? data.get("correctOptionIds") : wrong));
        }
        if (type.equals("判断题")) return JSON.valueToTree(Map.of("value", correct == data.path("correctAnswer").asBoolean()));
        Map<String, Object> responses = new LinkedHashMap<>();
        for (JsonNode part : data.path("parts")) {
            String id = part.path("id").asString();
            String text = correct ? "缓存减少重复计算，需要注意一致性和过期数据" : "缓存让计算变慢且永远不需要更新";
            Object value = type.equals("简答题") ? JSON.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}")
            : Map.of("type", "qf-answer-area", "formatVersion", 1, "id", "response-" + id, "width", 760,
                "height", part.path("answerHeight").asInt(480), "strokes", List.of(), "images", List.of(), "text", text);
            responses.put(id, value);
        }
        return JSON.valueToTree(Map.of("responses", responses));
    }

    private JsonNode board(String id) {
        var doc = JSON.createObjectNode().put("schemaVersion", 1);
        doc.set("viewport", JSON.valueToTree(Map.of("x", 0, "y", 0, "zoom", 1)));
        doc.set("paper", JSON.valueToTree(Map.of("color", "#ffffff", "pattern", "plain")));
        var strokes = doc.putArray("strokes");
        if (id != null)strokes.add(JSON.valueToTree(Map.of("id", id, "color", "#334155", "width", 2, "points", List.of(Map.of("x", 1, "y", 2, "pressure", .5)))));
        return doc;
    }

    private static Path temporary() {
        try {
            return Files.createTempDirectory("qf-exam-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Path directory(String name) {
        try {
            return Files.createDirectory(ROOT.resolve(name));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
