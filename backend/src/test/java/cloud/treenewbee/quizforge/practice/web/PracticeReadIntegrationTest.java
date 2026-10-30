package cloud.treenewbee.quizforge.practice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cloud.treenewbee.quizforge.practice.projection.ProjectionCacheTestSupport.backing;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.practice.projection.*;
import cloud.treenewbee.quizforge.rules.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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

@Timeout(180)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(PracticeReadIntegrationTest.GateConfig.class)
class PracticeReadIntegrationTest extends MySqlTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final String TOKEN = "read-integration-token";
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
        registry.add("qf.rule-runner.base-url", () -> "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090));
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired AuthService auth;
    @Autowired ImportService imports;
    @Autowired PracticeReadService reads;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContextInsertGate insertGate;
    @Autowired ApplicationContext application;
    @Autowired cloud.treenewbee.quizforge.content.infrastructure.BlobStore blobs;
    @MockitoSpyBean RuleRunnerClient runner;
    @MockitoSpyBean ProjectionCache cache;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<String> users = new ArrayList<>();
    String user, cookie, collection, bank;
    CacheStats statsBefore;

    @BeforeEach void fixture() throws Exception {
        reset(runner, cache);
        var account = auth.register(Ids.uuidV7() + "@example.test", "读题测试", "password-123", "read-test");
        user = account.userId(); cookie = "__Host-qf_session=" + account.token(); users.add(user);
        var imported = imports.importArchive(user, "single-choice.qbank",
                new ByteArrayInputStream(Files.readAllBytes(sample("single-choice.qbank"))));
        collection = imported.getFirst().id(); bank = imported.getFirst().bankId();
        approve();
        clearInvocations(runner, cache);
        backing(cache).invalidateAll(); backing(cache).cleanUp();
        statsBefore = backing(cache).stats();
    }

    @AfterEach void cleanup() {
        insertGate.collection = null;
        reset(runner, cache);
        backing(cache).invalidateAll(); backing(cache).cleanUp();
        approve();
        for (String owner : users) {
            jdbc.update("DELETE w FROM practice_whiteboard w JOIN practice_context c ON c.id=w.context_id WHERE c.user_id=?", owner);
            jdbc.update("DELETE s FROM practice_question_state s JOIN practice_context c ON c.id=s.context_id WHERE c.user_id=?", owner);
            jdbc.update("DELETE FROM practice_context WHERE user_id=?", owner);
            jdbc.update("DELETE FROM practice_collection WHERE user_id=?", owner);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", owner);
            jdbc.update("DELETE FROM account_user WHERE id=?", owner);
        }
    }

    @AfterAll static void stopRunner() throws Exception {
        RUNNER.stop();
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test void overviewCreatesOneContextAndLeftJoinsUnansweredRowsWithoutRawAnswers() throws Exception {
        var response = get(base(), cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = body(response);
        String qid = qid();
        assertThat(body.path("questions").get(0).path("id").asString()).isEqualTo(qid);
        assertThat(body.path("questions").get(0).path("type").path("pageId").asString()).matches("[a-f0-9]{64}");
        assertThat(body.path("states").path(qid).path("status").asString()).isEqualTo("unanswered");
        assertThat(body.path("states").path(qid).path("revision").asLong()).isZero();
        assertThat(body.path("availability").path("available").asBoolean()).isTrue();
        assertNoAnswers(response.body());
        get(base(), cookie);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_context WHERE collection_id=?", Integer.class, collection)).isEqualTo(1);
        verifyNoInteractions(runner);
    }

    @Test void fullApplicationContextSelectsProductionCaffeineAndHasNoNoopBean() {
        // Component scanning can also discover @Component classes under test-classes.
        // Require the implementation to ship from the same production output as its API.
        assertThat(CaffeineProjectionCache.class.getProtectionDomain().getCodeSource().getLocation())
                .isEqualTo(ProjectionCache.class.getProtectionDomain().getCodeSource().getLocation());
        assertThat(application.getBeansOfType(ProjectionCache.class).values()).containsExactly(cache);
        assertThat(application.getBean(ProjectionCache.class)).isInstanceOf(CaffeineProjectionCache.class);
        assertThat(application.getBeansOfType(NoopProjectionCache.class)).isEmpty();
        assertThat(application.getBean(CaffeineProjectionCache.class)).isSameAs(cache);
    }

    @Test void fiveRealTypesImportOpenAndProjectThroughTheOnlyCacheBoundary() throws Exception {
        var imported = imports.importArchive(user, "basic-types.qbank", new ByteArrayInputStream(Files.readAllBytes(sample("basic-types.qbank"))));
        approve(); clearInvocations(runner, cache);
        for (var item : imported) {
            String path = "/api/collections/bank/" + item.id();
            JsonNode overview = body(get(path, cookie));
            String qid = overview.path("questions").get(0).path("id").asString();
            var response = get(path + "/questions/" + qid, cookie);
            assertThat(response.statusCode()).isEqualTo(200);
            assertNoAnswers(response.body());
            JsonNode question = body(response);
            assertThat(question.path("question").path("data").isObject()).isTrue();
            assertThat(question.path("capabilities").path("canSave").asBoolean()).isTrue();
            assertThat(question.path("capabilities").path("canSubmit").asBoolean()).isFalse();
            String page = "/api/extensions/" + question.path("extension").path("pageId").asString()
                    + "/" + question.path("extension").path("version").asString() + "/page";
            assertThat(body(get(page, cookie)).path("script").asString()).contains("QF.");
        }
        verify(cache, times(5)).get(any(ProjectionKey.class), any());
        verify(runner, times(5)).execute(eq("project"), any(), anyMap());
    }

    @Test void repeatedUnconditionalReadsHitCaffeineButMatchingStampSkipsRunnerAndCache() throws Exception {
        JsonNode first = body(get(questionPath(), cookie));
        JsonNode second = body(get(questionPath(), cookie));
        assertThat(second.path("question").path("data")).isEqualTo(first.path("question").path("data"));
        verify(runner, times(1)).execute(eq("project"), any(), anyMap());
        String rawHash = jdbc.queryForObject("SELECT LOWER(HEX(content_sha256)) FROM content_question WHERE bank_id=? LIMIT 1", String.class, bank);
        verify(cache, times(2)).get(eq(new ProjectionKey(rawHash, digest())), any());
        clearInvocations(runner, cache);
        var unchanged = get(questionPath(), cookie, first.path("stamp").toString());
        assertThat(body(unchanged).path("unchanged").asBoolean()).isTrue();
        assertThat(body(unchanged).size()).isEqualTo(2);
        verifyNoInteractions(runner, cache);
        // The validator cannot be used as an oracle for raw answer-dependent content hashes.
        assertThat(first.path("stamp").path("contentVersion").asString()).isNotEqualTo(rawHash);
        assertCacheStats("same-user-two-reads", 1, 1, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"garbage", "null", "{}", "{\"revision\":-1,\"contentVersion\":\"x\",\"packageVersion\":\"y\"}"})
    void invalidStampIs400(String stamp) throws Exception {
        error(get(questionPath(), cookie, stamp), 400, "VALIDATION_FAILED");
        verifyNoInteractions(runner, cache);
    }

    @Test void draftReturnsOnlyMyAnswerRevisionAndWhiteboardAndCallsProjectOutsideTransaction() throws Exception {
        get(base(), cookie);
        jdbc.update("""
                INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,revision,content_sha256,updated_at)
                SELECT ?, question_id, ?, 'draft', '{"selectedOptionId":"my-answer"}', 4, content_sha256, NOW(3)
                  FROM content_question WHERE bank_id=?
                """, context(), user, bank);
        jdbc.update("INSERT INTO practice_whiteboard (context_id,question_id,user_id,draft,size_bytes,updated_at) VALUES (?,?,?,'{\"strokes\":[]}',14,NOW(3))", context(), qid(), user);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(runner).execute(eq("project"), any(), anyMap());
        JsonNode value = body(get(questionPath(), cookie));
        assertThat(value.path("state").path("answer").path("selectedOptionId").asString()).isEqualTo("my-answer");
        assertThat(value.path("state").path("revision").asLong()).isEqualTo(4);
        assertThat(value.path("draft").path("strokes").isArray()).isTrue();
        assertNoAnswers(value.toString());
    }

    @Test void submittedReturnsStoredProjectionWithoutCallingRunnerOrCache() throws Exception {
        get(base(), cookie);
        jdbc.update("""
                INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,result,projection,revision,content_sha256,updated_at)
                SELECT ?,question_id,?,'submitted','{"selectedOptionId":"mine"}','{"score":1}','{"frozen":"stored-projection"}',9,content_sha256,NOW(3)
                  FROM content_question WHERE bank_id=?
                """, context(), user, bank);
        JsonNode value = body(get(questionPath(), cookie));
        assertThat(value.path("question").path("data").path("frozen").asString()).isEqualTo("stored-projection");
        assertThat(value.path("state").path("revision").asLong()).isEqualTo(9);
        verifyNoInteractions(runner, cache);
        assertThat(backing(cache).asMap()).isEmpty();
        assertCacheStats("submitted-bypass", 0, 0, 0);
    }

    @Test void differentUsersShareOneCachedProjectionButKeepTheirOwnState() throws Exception {
        Viewer other = otherViewer();
        get(base(), cookie);
        jdbc.update("""
                INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,revision,content_sha256,updated_at)
                SELECT ?,question_id,?,'draft','{"selectedOptionId":"private-to-first-user"}',3,content_sha256,NOW(3)
                FROM content_question WHERE bank_id=?
                """, context(), user, bank);
        JsonNode first = body(get(questionPath(), cookie));
        JsonNode second = body(get(other.questionPath(qid()), other.cookie()));
        JsonNode again = body(get(questionPath(), cookie));
        assertThat(first.path("question").path("data")).isEqualTo(second.path("question").path("data"))
                .isEqualTo(again.path("question").path("data"));
        assertThat(first.path("state").path("answer").path("selectedOptionId").asString()).isEqualTo("private-to-first-user");
        assertThat(second.path("state").path("status").asString()).isEqualTo("unanswered");
        assertThat(second.path("state").path("revision").asLong()).isZero();
        assertThat(second.toString()).doesNotContain("private-to-first-user");
        verify(runner, times(1)).execute(eq("project"), any(), anyMap());
        verify(cache, times(3)).get(eq(cacheKey()), any());
        assertThat(backing(cache).asMap()).hasSize(1).containsKey(cacheKey());
        assertThat(backing(cache).asMap().get(cacheKey())).doesNotContain("private-to-first-user");
        assertNoAnswers(backing(cache).asMap().get(cacheKey()));
        assertCacheStats("two-users-three-reads", 2, 1, 1);
    }

    @Test void readingSubmittedAnswersCannotPoisonAnotherUsersUnsubmittedProjection() throws Exception {
        Viewer other = otherViewer();
        get(base(), cookie);
        assertThat(jdbc.queryForObject("SELECT data FROM content_question WHERE bank_id=? LIMIT 1", String.class, bank))
                .contains("correctOptionId");
        jdbc.update("""
                INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,result,projection,revision,content_sha256,updated_at)
                SELECT ?,question_id,?,'submitted','{"selectedOptionId":"mine"}','{"score":1}',
                       '{"correctOptionId":"submitted-only-secret","explanation":"submission-private"}',9,content_sha256,NOW(3)
                FROM content_question WHERE bank_id=?
                """, context(), user, bank);
        JsonNode submitted = body(get(questionPath(), cookie));
        assertThat(submitted.path("question").path("data").path("correctOptionId").asString()).isEqualTo("submitted-only-secret");
        assertThat(backing(cache).asMap()).isEmpty();
        verifyNoInteractions(runner, cache);
        var first = get(other.questionPath(qid()), other.cookie());
        var second = get(other.questionPath(qid()), other.cookie());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertNoAnswers(first.body()); assertNoAnswers(second.body());
        assertThat(first.body()).doesNotContain("submitted-only-secret", "submission-private");
        assertThat(second.body()).doesNotContain("submitted-only-secret", "submission-private");
        assertThat(body(first).path("question").path("data")).isEqualTo(body(second).path("question").path("data"));
        assertThat(body(first).path("state").path("status").asString()).isEqualTo("unanswered");
        String cached = backing(cache).asMap().get(cacheKey());
        assertThat(cached).isNotNull().doesNotContain("submitted-only-secret", "submission-private");
        assertNoAnswers(cached);
        // A submitted read must still bypass an already warm unsubmitted cache.
        assertThat(body(get(questionPath(), cookie)).path("question").path("data")).isEqualTo(submitted.path("question").path("data"));
        verify(cache, times(2)).get(eq(cacheKey()), any());
        verify(runner, times(1)).execute(eq("project"), any(), argThat(input -> {
            Object state = input.get("state");
            return state instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("submitted"))
                    && JSON.nullNode().equals(map.get("result"));
        }));
        assertCacheStats("submitted-then-other-user-unsubmitted", 1, 1, 1);
    }

    @Test void leftJoinKeepsQuestionsInPositionOrderAndNeverAttachesAnotherUsersState() throws Exception {
        get(base(), cookie);
        jdbc.update("""
                INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256,outline)
                SELECT bank_id,'Second',1,'第二题',extension_digest,data,max_score,content_sha256,'{"level":"parts","items":[{"id":"part","label":"小题"}]}'
                FROM content_question WHERE bank_id=? LIMIT 1
                """, bank);
        try {
            jdbc.update("""
                    INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,revision,content_sha256,updated_at)
                    SELECT ?,question_id,?,'draft','{"secret":"other-user"}',7,content_sha256,NOW(3)
                    FROM content_question WHERE bank_id=? AND question_id='Second'
                    """, context(), Ids.uuidV7(), bank);
            JsonNode overview = body(get(base(), cookie));
            assertThat(overview.path("questions").get(0).path("id").asString()).isEqualTo(qid());
            assertThat(overview.path("questions").get(1).path("id").asString()).isEqualTo("Second");
            assertThat(overview.path("questions").get(1).path("outlineItems").get(0).path("id").asString()).isEqualTo("part");
            assertThat(overview.path("states").path("Second").path("revision").asLong()).isZero();
            assertThat(get(base() + "/questions/Second", cookie).body()).doesNotContain("other-user");
            error(get(base() + "/questions/second", cookie), 404, "NOT_FOUND");
        } finally {
            jdbc.update("DELETE FROM practice_question_state WHERE context_id=? AND question_id='Second'", context());
            jdbc.update("DELETE FROM content_question WHERE bank_id=? AND question_id='Second'", bank);
        }
    }

    @Test void concurrentOpensCreateOnlyOneNormalPracticeContext() throws Exception {
        insertGate.collection = collection;
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = Stream.generate(() -> pool.submit(() -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start");
                return reads.overview(user, collection);
            })).limit(2).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var task : tasks) assertThat(task.get(30, TimeUnit.SECONDS).questions()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_context WHERE collection_id=? AND kind='practice' AND scope_key='default'", Integer.class, collection)).isEqualTo(1);
        assertThat(insertGate.connections).hasSize(2);
    }

    @Test void listeningMetadataHidesPathsAndHeadUsesTheSameOwnershipChecks() throws Exception {
        String sha = blobs.put("ID3-read-test-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO content_bank_asset (bank_id,path,blob_sha256) VALUES (?,'assets/LISTENING.MP3',UNHEX(?))", bank, sha);
        jdbc.update("UPDATE content_bank SET listening='{\"materials\":[{\"id\":\"m1\",\"title\":\"听力材料\",\"file\":\"assets/LISTENING.MP3\"}]}' WHERE id=?", bank);
        try {
            JsonNode listening = body(get(base(), cookie)).path("listening");
            assertThat(listening.toString()).doesNotContain("assets/", "file");
            assertThat(listening.path("materials").get(0).path("revision").asString()).matches("[a-f0-9]{64}");
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + base() + "/listening/m1"))
                    .header("Cookie", cookie).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("audio/mpeg");
            assertThat(response.headers().firstValue("X-Accel-Redirect")).hasValue("/_qf_blobs/" + sha.substring(0,2) + "/" + sha.substring(2,4) + "/" + sha);
            error(get(base() + "/listening/missing", cookie), 404, "NOT_FOUND");
        } finally {
            jdbc.update("UPDATE content_bank SET listening=NULL WHERE id=?", bank);
            jdbc.update("DELETE FROM content_bank_asset WHERE bank_id=? AND path='assets/LISTENING.MP3'", bank);
        }
    }

    @Test void sameIdAndVersionWithDifferentDigestServeTheExactReferencedPage() throws Exception {
        String original = digest();
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(sample("single-choice.qbank")))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        String script = "extensions/基础题型/单选题/practice.js";
        files.put(script, ("// exact digest variation\n" + new String(files.get(script), java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var added = imports.importArchive(user, "variant.qbank", new ByteArrayInputStream(cloud.treenewbee.quizforge.content.domain.ImportArchives.zip(files)));
        approve();
        String variant = jdbc.queryForObject("SELECT LOWER(HEX(extension_digest)) FROM content_question WHERE bank_id=? LIMIT 1", String.class, added.getFirst().bankId());
        assertThat(variant).isNotEqualTo(original);
        assertThat(body(get("/api/extensions/" + original + "/" + version() + "/page", cookie)).path("script").asString()).doesNotContain("exact digest variation");
        assertThat(body(get("/api/extensions/" + variant + "/" + version() + "/page", cookie)).path("script").asString()).startsWith("// exact digest variation");
    }

    @Test void otherUsersDeletedCollectionsAndMissingQuestionsAre404() throws Exception {
        var other = auth.register(Ids.uuidV7() + "@example.test", "其他用户", "password-123", "read-test"); users.add(other.userId());
        String otherCookie = "__Host-qf_session=" + other.token();
        error(get(base(), otherCookie), 404, "NOT_FOUND");
        error(get(questionPath(), otherCookie), 404, "NOT_FOUND");
        error(get(base() + "/questions/missing", cookie), 404, "NOT_FOUND");
        assertThat(body(get("/api/catalog", otherCookie)).path("banks").isEmpty()).isTrue();
        jdbc.update("UPDATE practice_collection SET deleted_at=NOW(3) WHERE id=?", collection);
        error(get(base(), cookie), 404, "NOT_FOUND");
        error(get(questionPath(), cookie), 404, "NOT_FOUND");
    }

    @Test void anonymousReadsRequireAuthentication() throws Exception {
        for (String path : List.of(base(), questionPath(), "/api/catalog", "/api/extensions/" + digest() + "/" + version() + "/page",
                "/api/sdk/quizforge.richtext/1.1.3", "/api/resources/" + asset())) {
            error(get(path, null), 401, "UNAUTHENTICATED");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "revoked", "rejected", "absent"})
    void reviewChangesBlockOverviewQuestionConditionalReadAndAllResourcesImmediately(String status) throws Exception {
        String stamp = body(get(questionPath(), cookie)).path("stamp").toString();
        if (status.equals("absent")) jdbc.update("DELETE r FROM admin_extension_review r JOIN content_question q ON q.extension_digest=r.digest WHERE q.bank_id=?", bank);
        else jdbc.update("UPDATE admin_extension_review SET status=? WHERE digest=UNHEX(?)", status, digest());
        var catalog = body(get("/api/library/index", cookie));
        assertThat(catalog.path("banks").get(0).path("availability").path("message").asString()).isEqualTo("审核中");
        error(get(base(), cookie), 422, "CLOUD_EXTENSION_REVIEW_REQUIRED");
        error(get(questionPath(), cookie, stamp), 422, "CLOUD_EXTENSION_REVIEW_REQUIRED");
        error(get("/api/extensions/" + digest() + "/" + version() + "/page", cookie), 404, "NOT_FOUND");
        error(get("/api/sdk/quizforge.richtext/1.1.3", cookie), 404, "NOT_FOUND");
        error(get("/api/resources/" + asset(), cookie), 404, "NOT_FOUND");
    }

    @Test void pageSdkAndImageRequireLiveReferencesAndExactDigestVersion() throws Exception {
        JsonNode page = body(get("/api/extensions/" + digest() + "/" + version() + "/page", cookie));
        assertThat(page.path("html").asString()).contains("<");
        assertThat(page.path("dependencies").get(0).path("version").asString()).isEqualTo("1.1.3");
        assertThat(page.path("contentApi").path("documentProfile").asString()).isEqualTo("advanced-v1");
        var sdk = get("/api/sdk/quizforge.richtext/1.1.3", cookie);
        assertThat(sdk.statusCode()).isEqualTo(200);
        assertThat(sdk.headers().firstValue("X-Accel-Redirect")).hasValue("/_qf_sdk/quizforge.richtext/1.1.3.json");
        assertThat(get("/api/sdk/quizforge.richtext/1.1.3/editor", cookie).statusCode()).isEqualTo(200);
        var image = get("/api/resources/" + asset(), cookie);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(image.headers().firstValue("X-Accel-Redirect")).hasValue("/_qf_blobs/" + asset().substring(0,2) + "/" + asset().substring(2,4) + "/" + asset());
        for (String path : List.of("/api/sdk/quizforge.richtext/1.0.0", "/api/sdk/quizforge.answer-area/1.4.1",
                "/api/extensions/" + digest() + "/wrong/page", "/api/extensions/quizforge.single-choice/" + version() + "/page",
                "/api/resources/" + "f".repeat(64), "/api/resources/bad")) error(get(path, cookie), 404, "NOT_FOUND");
        var other = auth.register(Ids.uuidV7() + "@example.test", "无题库用户", "password-123", "read-test"); users.add(other.userId());
        String otherCookie = "__Host-qf_session=" + other.token();
        for (String path : List.of("/api/extensions/" + digest() + "/" + version() + "/page", "/api/sdk/quizforge.richtext/1.1.3", "/api/resources/" + asset())) {
            error(get(path, otherCookie), 404, "NOT_FOUND");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"RULE_TIMEOUT", "RULE_REJECTED", "PRIVATE_INTERNAL_CODE"})
    void ruleRejectionNeverFallsBackToRawQuestion(String code) throws Exception {
        doReturn(RuleResult.rejected(code)).when(runner).execute(eq("project"), any(), anyMap());
        var response = get(questionPath(), cookie);
        error(response, 422, code.equals("PRIVATE_INTERNAL_CODE") ? "RULE_REJECTED" : code);
        assertNoAnswers(response.body());
    }

    @Test @Order(Integer.MAX_VALUE) void stoppedRunnerIs503WithoutRawDataFallback() throws Exception {
        RUNNER.stop();
        var response = get(questionPath(), cookie);
        error(response, 503, "RULE_UNAVAILABLE");
        assertNoAnswers(response.body());
    }

    @Test void missingProjectedFieldIs503WithoutRawDataFallback() throws Exception {
        doReturn(RuleResult.success(JSON.readTree("{}"))).when(runner).execute(eq("project"), any(), anyMap());
        var response = get(questionPath(), cookie);
        error(response, 503, "RULE_UNAVAILABLE");
        assertNoAnswers(response.body());
    }

    private void approve() {
        jdbc.update("""
                INSERT INTO admin_extension_review (digest,extension_id,version,status)
                SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' FROM content_extension_package e
                JOIN content_question q ON q.extension_digest=e.digest
                JOIN practice_collection c ON c.bank_id=q.bank_id WHERE c.user_id=?
                ON DUPLICATE KEY UPDATE status='approved'
                """, user);
    }
    private void assertCacheStats(String scenario, long hits, long misses, long loads) {
        CacheStats stats = backing(cache).stats().minus(statsBefore);
        assertThat(stats.hitCount()).isEqualTo(hits);
        assertThat(stats.missCount()).isEqualTo(misses);
        assertThat(stats.loadSuccessCount()).isEqualTo(loads);
        assertThat(stats.loadFailureCount()).isZero();
        assertThat(stats.evictionCount()).isZero();
        System.out.printf(Locale.ROOT,
                "CACHE_STATS scenario=%s hitCount=%d missCount=%d loadSuccessCount=%d loadFailureCount=%d evictionCount=%d hitRate=%.6f%n",
                scenario, stats.hitCount(), stats.missCount(), stats.loadSuccessCount(), stats.loadFailureCount(), stats.evictionCount(), stats.hitRate());
    }
    private ProjectionKey cacheKey() {
        String hash = jdbc.queryForObject("SELECT LOWER(HEX(content_sha256)) FROM content_question WHERE bank_id=? LIMIT 1", String.class, bank);
        return new ProjectionKey(hash, digest());
    }
    private Viewer otherViewer() {
        var other = auth.register(Ids.uuidV7() + "@example.test", "缓存共享用户", "password-123", "cache-test");
        users.add(other.userId());
        String id = Ids.uuidV7();
        jdbc.update("""
                INSERT INTO practice_collection (id,user_id,bank_id,title,group_name,source,created_at)
                SELECT ?,?,bank_id,title,group_name,source,NOW(3) FROM practice_collection WHERE id=?
                """, id, other.userId(), collection);
        return new Viewer("__Host-qf_session=" + other.token(), id);
    }
    private record Viewer(String cookie, String collection) {
        String questionPath(String qid) { return "/api/collections/bank/" + collection + "/questions/" + qid; }
    }
    private String context() { return jdbc.queryForObject("SELECT id FROM practice_context WHERE collection_id=? AND kind='practice' AND scope_key='default'", String.class, collection); }
    private String qid() { return jdbc.queryForObject("SELECT question_id FROM content_question WHERE bank_id=? ORDER BY position LIMIT 1", String.class, bank); }
    private String digest() { return jdbc.queryForObject("SELECT LOWER(HEX(extension_digest)) FROM content_question WHERE bank_id=? LIMIT 1", String.class, bank); }
    private String version() { return jdbc.queryForObject("SELECT version FROM content_extension_package WHERE digest=UNHEX(?)", String.class, digest()); }
    private String asset() { return jdbc.queryForObject("SELECT LOWER(HEX(blob_sha256)) FROM content_bank_asset WHERE bank_id=? LIMIT 1", String.class, bank); }
    private String base() { return "/api/collections/bank/" + collection; }
    private String questionPath() { return base() + "/questions/" + qid(); }
    private HttpResponse<String> get(String path, String cookie) throws Exception { return get(path, cookie, null); }
    private HttpResponse<String> get(String path, String cookie, String stamp) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(90));
        if (cookie != null) request.header("Cookie", cookie);
        if (stamp != null) request.header("X-QuizForge-Question-Stamp", stamp);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode body(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(body(response).path("error").path("code").asString()).isEqualTo(code);
    }
    private static void assertNoAnswers(String response) {
        assertThat(response).doesNotContain("\"correctOptionId\"", "\"correctOptionIds\"", "\"correctAnswer\"", "\"answerAndExplanation\"");
    }
    private static Path sample(String filename) { return Path.of(System.getProperty("basedir", ".")).resolve("../samples/" + filename); }
    private static Path temporary() { try { return Files.createTempDirectory("qf-read-"); } catch (Exception error) { throw new ExceptionInInitializerError(error); } }
    private static Path directory(String name) { try { return Files.createDirectory(ROOT.resolve(name)); } catch (Exception error) { throw new ExceptionInInitializerError(error); } }

    @TestConfiguration static class GateConfig {
        @Bean ContextInsertGate contextInsertGate() { return new ContextInsertGate(); }
    }
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,
            method="update", args={org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static final class ContextInsertGate implements org.apache.ibatis.plugin.Interceptor {
        volatile String collection;
        final CyclicBarrier both = new CyclicBarrier(2);
        final Set<Long> connections = ConcurrentHashMap.newKeySet();
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            if (collection != null && statement.getId().endsWith("PracticeReadMapper.insertContext")
                    && collection.equals(((Map<?,?>) invocation.getArgs()[1]).get("collectionId"))) {
                var connection = ((org.apache.ibatis.executor.Executor) invocation.getTarget()).getTransaction().getConnection();
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next(); connections.add(rows.getLong(1));
                }
                both.await(10, TimeUnit.SECONDS);
            }
            return invocation.proceed();
        }
    }
}
