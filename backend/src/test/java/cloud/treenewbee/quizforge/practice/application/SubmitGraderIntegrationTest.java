package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionMapper;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionRow;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.rules.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(120)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubmitGraderIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final String TOKEN = "submit-grader-test";
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
        registry.add("qf.rule-runner.base-url", SubmitGraderIntegrationTest::runnerUrl);
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired AuthService auth;
    @Autowired ImportService imports;
    @Autowired SubmitGrader grader;
    @Autowired PracticeReadService reads;
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
        jdbc.update("""
                INSERT INTO admin_extension_review (digest,extension_id,version,status)
                SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' FROM content_extension_package e
                JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id
                WHERE c.user_id=? ON DUPLICATE KEY UPDATE status='approved'
                """, user);
    }

    @BeforeEach void resetSpy() { reset(runner); }
    @AfterEach void noPracticeWrites() {
        reset(runner);
        for (String table : List.of("practice_context", "practice_question_state", "practice_round", "practice_round_question", "practice_request_receipt"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, user)).as(table).isZero();
    }

    @AfterAll void cleanup() throws Exception {
        reset(runner);
        jdbc.update("DELETE FROM practice_collection WHERE user_id=?", user);
        jdbc.update("DELETE FROM auth_session WHERE user_id=?", user);
        jdbc.update("DELETE FROM account_user WHERE id=?", user);
        RUNNER.stop();
        try (var files = Files.walk(ROOT)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }

    @ParameterizedTest @ValueSource(strings = {"单选题", "多选题", "判断题", "简答题", "简答题（答题区）"})
    void referenceAnswerUsesRealSubmitWithScoreAndPreservesEveryOutcomeField(String type) {
        assertRealGrade(type, true);
    }

    @ParameterizedTest @ValueSource(strings = {"单选题", "多选题", "判断题", "简答题", "简答题（答题区）"})
    void wrongAnswerUsesRealRulesRatherThanBeingTreatedAsInvalid(String type) {
        assertRealGrade(type, false);
    }

    @ParameterizedTest @ValueSource(strings = {"单选题", "多选题", "判断题", "简答题", "简答题（答题区）"})
    void invalidAnswerOfEveryBasicTypeIs422RuleRejected(String type) {
        var sample = samples.get(type);
        assertApi(() -> grader.grade(user, sample.collection(), sample.question().questionId(), JSON.readTree("{\"invalid\":true}")),
                HttpStatus.UNPROCESSABLE_CONTENT, "RULE_REJECTED");
        verify(runner, times(1)).execute(eq("submit"), any(), anyMap());
    }

    @Test void answerWithValidSchemaButUnknownOptionIsRejectedByHook() {
        var sample = samples.get("单选题");
        assertApi(() -> grader.grade(user, sample.collection(), sample.question().questionId(), JSON.readTree("{\"selectedOptionId\":\"missing-option\"}")),
                HttpStatus.UNPROCESSABLE_CONTENT, "RULE_REJECTED");
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"RULE_REJECTED", "RULE_TIMEOUT", "SCORE_UNAVAILABLE", "INVALID_OUTLINE_ITEMS", "INVALID_OUTLINE_STATES", "UNSUPPORTED_API_VERSION", "internal/path/unknown"})
    void rejectionCodesAreAllowlistedAndNeverExposeInternalCodes(String code) {
        doReturn(RuleResult.rejected(code)).when(runner).execute(eq("submit"), any(), anyMap());
        var sample = samples.get("单选题");
        String expected = code == null || code.startsWith("internal/") ? "RULE_REJECTED" : code;
        assertApi(() -> grader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true)),
                HttpStatus.UNPROCESSABLE_CONTENT, expected);
        verify(runner, times(1)).execute(eq("submit"), any(), anyMap());
    }

    @Test void ruleRunnerExceptionIsPropagatedAsTheSameInstance() {
        var unavailable = new RuleRunnerException("test unavailable");
        doThrow(unavailable).when(runner).execute(eq("submit"), any(), anyMap());
        var sample = samples.get("单选题");
        assertThatThrownBy(() -> grader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true)))
                .isSameAs(unavailable);
    }

    @Test void realRunnerNon200RemainsRuleRunnerException() {
        var properties = new RuleRunnerProperties(URI.create(runnerUrl()), "incorrect-token", 2, Duration.ofSeconds(10));
        var realClient = new RuleRunnerClient(properties, JSON);
        var realGrader = new SubmitGrader(reads, realClient, JSON);
        var sample = samples.get("单选题");
        assertThatThrownBy(() -> realGrader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true)))
                .isInstanceOf(RuleRunnerException.class).hasMessageContaining("401");
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"result\":{},\"projected\":{}}", "{\"result\":{},\"projected\":{},\"score\":{\"score\":null,\"maxScore\":2,\"gradingStatus\":\"graded\"}}"})
    void malformedSuccessCannotBecomeAZeroScore(String data) {
        doReturn(RuleResult.success(JSON.readTree(data))).when(runner).execute(eq("submit"), any(), anyMap());
        var sample = samples.get("单选题");
        assertThatThrownBy(() -> grader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true)))
                .isInstanceOf(RuleRunnerException.class);
    }

    @Test void decimalScoreIsPreservedWithoutChoosingARoundingPolicy() {
        doReturn(RuleResult.success(JSON.readTree("""
                {"result":{},"projected":{},"score":{"score":0.123456,"maxScore":2.5,"gradingStatus":"graded","outlineStates":[]}}
                """))).when(runner).execute(eq("submit"), any(), anyMap());
        var sample = samples.get("单选题");
        var outcome = grader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true));
        assertThat(outcome.score()).isEqualByComparingTo("0.123456"); assertThat(outcome.maxScore()).isEqualByComparingTo("2.5");
        assertThat(outcome.outlineStatesJson()).isEqualTo("[]");
    }

    @Test void ownershipDeletedCollectionQuestionLookupAndApprovalUseReadPathBeforeRunner() {
        var sample = samples.get("单选题");
        assertApi(() -> grader.grade(Ids.uuidV7(), sample.collection(), sample.question().questionId(), answer("单选题", true)), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertApi(() -> grader.grade(user, sample.collection(), "missing-question", answer("单选题", true)), HttpStatus.NOT_FOUND, "NOT_FOUND");
        jdbc.update("UPDATE practice_collection SET deleted_at=NOW(3) WHERE id=?", sample.collection());
        try { assertApi(() -> grader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true)), HttpStatus.NOT_FOUND, "NOT_FOUND"); }
        finally { jdbc.update("UPDATE practice_collection SET deleted_at=NULL WHERE id=?", sample.collection()); }
        jdbc.update("UPDATE admin_extension_review SET status='revoked' WHERE digest=?", sample.question().extensionDigest());
        try { assertApi(() -> grader.grade(user, sample.collection(), sample.question().questionId(), answer("单选题", true)), HttpStatus.UNPROCESSABLE_CONTENT, "CLOUD_EXTENSION_REVIEW_REQUIRED"); }
        finally { jdbc.update("UPDATE admin_extension_review SET status='approved' WHERE digest=?", sample.question().extensionDigest()); }
        verifyNoInteractions(runner);
    }

    private void assertRealGrade(String type, boolean correct) {
        var observed = new AtomicReference<RuleResult>();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            RuleResult result = (RuleResult) call.callRealMethod(); observed.set(result); return result;
        }).when(runner).execute(eq("submit"), any(), anyMap());
        var sample = samples.get(type); JsonNode input = answer(type, correct);
        GradeOutcome outcome = grader.grade(user, sample.collection(), sample.question().questionId(), input);
        JsonNode raw = observed.get().data();
        assertThat(observed.get().ok()).isTrue();
        assertThat(JSON.readTree(outcome.resultJson())).isEqualTo(raw.get("result"));
        assertThat(JSON.readTree(outcome.projectedJson())).isEqualTo(raw.get("projected"));
        assertThat(outcome.maxScore()).isEqualByComparingTo(sample.question().maxScore());
        assertThat(outcome.gradingStatus()).isEqualTo(raw.path("score").path("gradingStatus").asString());
        if (raw.path("score").has("outlineStates")) assertThat(JSON.readTree(outcome.outlineStatesJson())).isEqualTo(raw.path("score").get("outlineStates"));
        else assertThat(outcome.outlineStatesJson()).isNull();
        if (type.equals("简答题（答题区）")) {
            assertThat(outcome.gradingStatus()).isEqualTo("pending"); assertThat(outcome.score()).isNull();
        } else {
            assertThat(outcome.gradingStatus()).isEqualTo("graded");
            assertThat(outcome.score()).isEqualByComparingTo(type.equals("简答题") || !correct ? BigDecimal.ZERO : sample.question().maxScore());
            if (!type.equals("简答题")) assertThat(raw.path("result").path("correct").asBoolean()).isEqualTo(correct);
        }
        JsonNode projected = JSON.readTree(outcome.projectedJson());
        if (type.equals("简答题") || type.equals("简答题（答题区）")) assertThat(projected.path("parts").get(0).has("answerAndExplanation")).isTrue();
        else assertThat(projected.has("answerAndExplanation")).isTrue();
        var expected = reads.ruleQuestion(user, sample.collection(), sample.question().questionId());
        verify(runner, times(1)).execute(eq("submit"), eq(expected.rulePackage()), eq(Map.of("data", JSON.readTree(sample.question().data()), "answer", input, "withScore", true)));
        verify(runner, times(1)).execute(eq("submit"), eq(expected.rulePackage()), eq(Map.of("data", JSON.readTree(sample.question().data()), "answer", input, "withScore", true)), eq(Duration.ofSeconds(60)));
        verifyNoMoreInteractions(runner);
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
    private static void assertApi(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status); assertThat(error.code()).isEqualTo(code);
        });
    }
    private static String runnerUrl() { return "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090); }
    private static Path temporary() { try { return Files.createTempDirectory("qf-submit-grader-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static Path directory(String name) { try { return Files.createDirectory(ROOT.resolve(name)); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
}
