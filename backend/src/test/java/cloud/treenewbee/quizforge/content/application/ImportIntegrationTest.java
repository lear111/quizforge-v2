package cloud.treenewbee.quizforge.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.rules.RulePackage;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(ImportIntegrationTest.TestClockConfig.class)
@Timeout(180)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportIntegrationTest extends MySqlTestBase {
    static final String TOKEN = "import-integration-token";
    static final String ORIGIN = "https://import.example.test";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path ROOT = temporary();
    static final Path BLOBS = directory("blobs"), UPLOADS = directory("uploads"), MULTIPART = directory("multipart");
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
            .withEnv("QF_RULE_WORKER_LIMIT", "2").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN))
            .withStartupTimeout(Duration.ofMinutes(2));
    static { RUNNER.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.storage.blob-dir", BLOBS::toString);
        registry.add("qf.import.temp-dir", UPLOADS::toString);
        registry.add("spring.servlet.multipart.location", MULTIPART::toString);
        registry.add("qf.security.allowed-origin", () -> ORIGIN);
        registry.add("qf.rule-runner.base-url", () -> "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090));
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired cloud.treenewbee.quizforge.testsupport.QueuedPracticeImports imports;
    @Autowired ImportRules rules;
    @Autowired ImportUploadService uploads;
    @Autowired ImportQueueJobs queue;
    @Autowired BankAvailability availability;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Value("${local.server.port}") int port;
    @MockitoSpyBean RuleRunnerClient runner;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<String> operations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void prepare() {
        clock.now.set(Instant.parse("2026-10-29T12:00:00Z"));
        clearBusiness(); reset(runner);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            RulePackage spec = call.getArgument(1);
            String op = call.getArgument(0);
            Map<String, ?> input = call.getArgument(2);
            assertThat(rules.envelopeBytes(spec, op, input)).isLessThanOrEqualTo(ImportRules.MAX_REQUEST_BYTES);
            operations.add(op);
            return call.callRealMethod();
        }).when(runner).execute(anyString(), any(RulePackage.class), anyMap());
    }

    @AfterEach
    void cleanup() throws Exception {
        reset(runner); clearBusiness();
        try (var files = Files.list(UPLOADS)) { assertThat(files.toList()).isEmpty(); }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (var files = Files.list(MULTIPART)) { assertThat(files.toList()).isEmpty(); }
        });
    }

    @AfterAll
    static void stopRunner() { RUNNER.stop(); }

    @Test
    void retiredSynchronousImportHasNoHttpHandler() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/library/import"))
                .header("Origin", ORIGIN).header("Cookie", cookie()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 404, "NOT_FOUND");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nearEightMiBBankIsSplitUsingWholeEnvelopeByteSize() throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "bank");
        String path = ImportArchives.bank("单选题") + "/bank.json";
        Map<String, Object> bank = JSON.readValue(files.get(path), LinkedHashMap.class);
        Map<String, Object> original = (Map<String, Object>) ((List<?>) bank.get("questions")).getFirst();
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int i=0; i<6; i++) {
            Map<String, Object> question = JSON.readValue(JSON.writeValueAsBytes(original), LinkedHashMap.class);
            question.put("id", "large-"+i);
            ((Map<String, Object>) question.get("data")).put("stem", Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content", new ArrayList<>()))));
            questions.add(question);
        }
        bank.put("questions", questions);
        bank.put("outline", List.of(Map.of("items", questions.stream().map(q -> Map.of("question", q.get("id"))).toList())));
        // Extra text nodes add their own JSON framing; tune the last short text after framing is known.
        for (Map<String, Object> question : questions) {
            Map<String, Object> data = (Map<String, Object>) question.get("data");
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (int n=0; n<14; n++) nodes.add(new LinkedHashMap<>(Map.of("type", "text", "text", question.get("id") + "x".repeat(98990))));
            data.put("stem", Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content", nodes))));
        }
        byte[] bankBytes = JSON.writeValueAsBytes(bank);
        int remaining = ImportRules.MAX_REQUEST_BYTES - 32 - bankBytes.length;
        assertThat(remaining).isPositive();
        Map<String, Object> data = (Map<String, Object>) questions.getLast().get("data");
        Map<String, Object> stem = (Map<String, Object>) data.get("stem");
        Map<String, Object> paragraph = (Map<String, Object>) ((List<?>)stem.get("content")).getFirst();
        List<Map<String,Object>> nodes = (List<Map<String,Object>>) paragraph.get("content");
        nodes.add(new LinkedHashMap<>(Map.of("type", "text", "text", "")));
        remaining = ImportRules.MAX_REQUEST_BYTES - 32 - JSON.writeValueAsBytes(bank).length;
        // Keep every text node within the real fixture schema's 100000-character bound.
        while (remaining > 99000) {
            nodes.getLast().put("text", "x".repeat(99000));
            nodes.add(new LinkedHashMap<>(Map.of("type", "text", "text", "")));
            remaining = ImportRules.MAX_REQUEST_BYTES - 32 - JSON.writeValueAsBytes(bank).length;
        }
        nodes.getLast().put("text", "x".repeat(remaining));
        files.put(path, JSON.writeValueAsBytes(bank));
        assertThat(files.get(path).length).isEqualTo(ImportRules.MAX_REQUEST_BYTES - 32);
        var response = upload(cookie(), ImportArchives.zip(files));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(operations.stream().filter("scoreBatch"::equals).count()).isGreaterThan(1);
        assertThat(count("content_question")).isEqualTo(6);
    }

    @Test
    void wholeGroupImportsAllFiveTypesWithScoresOutlinesAssetsAndIndependentInstances() throws Exception {
        byte[] archive = archive(ImportArchives.TYPES, "group");
        String cookie = cookie();
        var first = upload(cookie, archive);
        assertThat(first.statusCode()).isEqualTo(200);
        var collections = body(first).path("collections");
        assertThat(collections.size()).isEqualTo(5);
        assertThat(count("content_bank")).isEqualTo(5);
        assertThat(count("content_extension_package")).isEqualTo(5);
        assertThat(count("content_question")).isEqualTo(5);
        assertThat(count("content_bank_asset")).isEqualTo(5);
        assertThat(operations.stream().filter("capabilities"::equals)).hasSize(5);
        assertThat(operations).contains("validateBank", "outlineBatch", "scoreBatch");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_question WHERE max_score IS NULL OR max_score<0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE source_pack_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT source->>'$.filename' FROM practice_collection", String.class)).containsOnly("test.qbank");
        assertThat(first.body()).doesNotContain("correctOptionId", "answerAndExplanation");
        for (var collection : collections) assertThat(availability.isAvailable(collection.path("bankId").asString())).isTrue();
        var second = upload(cookie, archive);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(count("content_bank")).isEqualTo(5);
        assertThat(count("content_question")).isEqualTo(5);
        assertThat(count("content_bank_asset")).isEqualTo(5);
        assertThat(count("practice_collection")).isEqualTo(10);
        assertThat(body(second).path("collections").get(0).path("id").asString()).isNotEqualTo(collections.get(0).path("id").asString());
        assertThat(body(second).path("collections").get(0).path("bankId").asString()).isEqualTo(collections.get(0).path("bankId").asString());
    }

    static Stream<String> types() { return ImportArchives.TYPES.stream(); }

    @Test
    @SuppressWarnings("unchecked")
    void mixedPerQuestionBindingsKeepOrderScoresAndExplicitOutline() throws Exception {
        var files = ImportArchives.files(List.of("单选题", "判断题"), "bank");
        String singlePath = ImportArchives.bank("单选题") + "/bank.json", judgePath = ImportArchives.bank("判断题") + "/bank.json";
        Map<String, Object> bank = JSON.readValue(files.get(singlePath), LinkedHashMap.class);
        Map<String, Object> judge = JSON.readValue(files.get(judgePath), LinkedHashMap.class);
        Map<String,Object> first = (Map<String,Object>) ((List<?>)bank.get("questions")).getFirst();
        Map<String,Object> second = (Map<String,Object>) ((List<?>)judge.get("questions")).getFirst();
        Map<String,Object> third = JSON.readValue(JSON.writeValueAsBytes(first), LinkedHashMap.class);
        second.put("id", "judge"); second.put("extension", Map.of("path", ImportArchives.extension("判断题")));
        third.put("id", "single-2");
        ((Map<String,Object>)first.get("data")).put("maxScore", 2);
        ((Map<String,Object>)second.get("data")).put("maxScore", 3);
        ((Map<String,Object>)third.get("data")).put("maxScore", 7);
        bank.put("outline", List.of(Map.of("items", List.of(Map.of("question", first.get("id")), Map.of("question", "judge"), Map.of("question", "single-2", "label", "作者大纲")))));
        bank.put("questions", List.of(first, second, third)); files.put(singlePath, JSON.writeValueAsBytes(bank));
        files.keySet().removeIf(path -> path.startsWith(ImportArchives.bank("判断题") + "/"));
        var response = upload(cookie(), ImportArchives.zip(files));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT max_score FROM content_question ORDER BY position", java.math.BigDecimal.class))
                .containsExactly(new java.math.BigDecimal("2.0000"), new java.math.BigDecimal("3.0000"), new java.math.BigDecimal("7.0000"));
        var digests = jdbc.queryForList("SELECT HEX(extension_digest) FROM content_question ORDER BY position", String.class);
        assertThat(digests.get(0)).isEqualTo(digests.get(2)).isNotEqualTo(digests.get(1));
        assertThat(jdbc.queryForObject("SELECT outline->>'$.label' FROM content_question WHERE position=2", String.class)).isEqualTo("作者大纲");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedOutlineUsesPartsShapeAndNeverOverridesExplicitOutline() throws Exception {
        var files = ImportArchives.files(List.of("简答题"), "bank");
        String rulesPath = ImportArchives.extension("简答题") + "/rules.js";
        String source = new String(files.get(rulesPath), StandardCharsets.UTF_8);
        files.put(rulesPath, source.replace("getOutlineItems() {return [];}",
                "getOutlineItems() {return [{id:'part',label:'派生小题'}];}").getBytes(StandardCharsets.UTF_8));
        ImportArchives.edit(files, ImportArchives.bank("简答题") + "/bank.json", bank -> {
            Map<String, Object> first = (Map<String, Object>) ((List<?>) bank.get("questions")).getFirst();
            Map<String, Object> second = JSON.readValue(JSON.writeValueAsBytes(first), LinkedHashMap.class);
            second.put("id", "explicit");
            bank.put("outline", List.of(Map.of("items", List.of(Map.of("question", first.get("id")), Map.of("question", "explicit", "label", "作者大纲")))));
            bank.put("questions", List.of(first, second));
        });
        var response = upload(cookie(), ImportArchives.zip(files));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT outline->>'$.level' FROM content_question WHERE position=0", String.class)).isEqualTo("parts");
        assertThat(jdbc.queryForObject("SELECT outline->>'$.items[0].label' FROM content_question WHERE position=0", String.class)).isEqualTo("派生小题");
        assertThat(jdbc.queryForObject("SELECT outline->>'$.label' FROM content_question WHERE position=1", String.class)).isEqualTo("作者大纲");
        assertThat(operations.stream().filter("outlineBatch"::equals)).hasSize(1);
    }

    @Test
    void lateRuleFailureCannotPersistPreviouslyValidatedBanks() throws Exception {
        var files = ImportArchives.files(ImportArchives.TYPES, "group");
        files.put(ImportArchives.extension("简答题（答题区）") + "/rules.js",
                "QF.defineType({project(){return{}},grade(){},validateQuestion(){return false}})".getBytes(StandardCharsets.UTF_8));
        assertBackgroundFailure(rawUpload(cookie(), ImportArchives.zip(files)), "RULE_REJECTED");
        assertNoBusinessData();
    }

    @ParameterizedTest
    @MethodSource("types")
    void singleBankPackageImportsEachBasicType(String type) throws Exception {
        var response = upload(cookie(), archive(List.of(type), "bank"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("collections").size()).isEqualTo(1);
        assertThat(body(response).path("collections").get(0).path("title").asString()).isEqualTo(type);
        assertThat(count("content_question")).isEqualTo(1);
    }

    @Test
    void concurrentImportOfIdenticalContentCommitsOneBankAndTwoInstances() throws Exception {
        byte[] archive = archive(List.of("单选题"), "bank");
        String firstUser = userId(), secondUser = userId();
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> { start.await(); return imports.importArchive(firstUser, "one.qbank", new ByteArrayInputStream(archive)); });
            var second = threads.submit(() -> { start.await(); return imports.importArchive(secondUser, "two.qbank", new ByteArrayInputStream(archive)); });
            start.countDown();
            var a = first.get(60, TimeUnit.SECONDS).getFirst();
            var b = second.get(60, TimeUnit.SECONDS).getFirst();
            assertThat(a.bankId()).isEqualTo(b.bankId()); assertThat(a.id()).isNotEqualTo(b.id());
        }
        assertThat(count("content_bank")).isEqualTo(1); assertThat(count("content_question")).isEqualTo(1);
        assertThat(count("content_bank_asset")).isEqualTo(1); assertThat(count("practice_collection")).isEqualTo(2);
    }

    static Stream<Arguments> invalidPackages() {
        return Stream.of(
                Arguments.of("old format", "UNSUPPORTED_LIBRARY_ARCHIVE", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, "quizforge-package.json", m -> m.put("formatVersion", 3))),
                Arguments.of("layout", "INVALID_LIBRARY_ARCHIVE", (Consumer<Map<String, byte[]>>) f -> f.put("README.txt", new byte[1])),
                Arguments.of("resource", "UNSUPPORTED_RESOURCE_TYPE", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.bank("单选题") + "/assets/test.exe", new byte[8])),
                Arguments.of("bank structure", "INVALID_QUESTION_BANK", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.bank("单选题") + "/bank.json", m -> m.put("questions", List.of()))),
                Arguments.of("missing bank UUID", "INVALID_QUESTION_BANK", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.bank("单选题") + "/bank.json", m -> m.remove("id"))),
                Arguments.of("invalid bank UUID", "INVALID_QUESTION_BANK", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.bank("单选题") + "/bank.json", m -> m.put("id", "examples"))),
                Arguments.of("missing example UUID", "INVALID_QUESTION_BANK", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.extension("单选题") + "/examples/bank.json", m -> m.remove("id"))),
                Arguments.of("invalid example UUID", "INVALID_QUESTION_BANK", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.extension("单选题") + "/examples/bank.json", m -> m.put("id", "60000000-0000-4000-8000-00000000000A"))),
                Arguments.of("duplicate bank and example UUID", "INVALID_QUESTION_BANK", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.extension("单选题") + "/examples/bank.json", m -> m.put("id", ImportArchives.BANK_IDS.get("单选题")))),
                Arguments.of("manifest", "INVALID_EXTENSION", (Consumer<Map<String, byte[]>>) f -> ImportArchives.edit(f, ImportArchives.extension("单选题") + "/manifest.json", m -> m.remove("rules"))),
                Arguments.of("entry limit", "LIBRARY_ARCHIVE_LIMIT", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.bank("单选题") + "/assets/big.png", new byte[33*1024*1024])),
                Arguments.of("rule rejection", "RULE_REJECTED", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.extension("单选题") + "/rules.js", "QF.defineType({project(){return{}},grade(){},validateQuestion(){return false}})".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("rule timeout", "RULE_TIMEOUT", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.extension("单选题") + "/rules.js", "QF.defineType({project(){return{}},grade(){},validateQuestion(){while(true){}}})".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("invalid outline", "INVALID_OUTLINE_ITEMS", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.extension("单选题") + "/rules.js", "QF.defineType({project(){return{maxScore:1}},grade(){},getOutlineItems(){return[{id:'x',label:'a'},{id:'x',label:'b'}]}})".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("invalid outline state", "INVALID_OUTLINE_STATES", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.extension("单选题") + "/rules.js", "QF.defineType({project(){return{}},grade(){},getScore(){throw Object.assign(Error('invalid'),{code:'INVALID_OUTLINE_STATES'})}})".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("score range", "SCORE_UNAVAILABLE", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.extension("单选题") + "/rules.js", "QF.defineType({project(){return{}},grade(){},getScore(){return{score:0,maxScore:100000000}}})".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("score precision", "SCORE_UNAVAILABLE", (Consumer<Map<String, byte[]>>) f -> f.put(ImportArchives.extension("单选题") + "/rules.js", "QF.defineType({project(){return{}},grade(){},getScore(){return{score:0,maxScore:1.23456}}})".getBytes(StandardCharsets.UTF_8)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPackages")
    void everyFailureHasCorrectQueueCodeAndNoBusinessResidue(String name, String code, Consumer<Map<String, byte[]>> mutate) throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "bank"); mutate.accept(files);
        var accepted = rawUpload(cookie(), ImportArchives.zip(files));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        assertBackgroundFailure(accepted, code);
        assertNoBusinessData();
    }

    @Test
    void nonZipIsRejectedByQueueAndUploadRequiresFilename() throws Exception {
        assertBackgroundFailure(rawUpload(cookie(), new byte[] {1,2,3}), "INVALID_LIBRARY_ARCHIVE");
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/library/uploads"))
                .header("Origin", ORIGIN).header("Cookie", cookie()).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 400, "VALIDATION_FAILED");
        assertNoBusinessData();
    }

    @Test
    void databaseFailureRollsBackAllBanksExtensionsQuestionsAssetsAndInstances() throws Exception {
        jdbc.execute("ALTER TABLE content_bank ADD CONSTRAINT import_test_failure CHECK (source_title <> '单选题')");
        try { assertBackgroundFailure(rawUpload(cookie(), archive(ImportArchives.TYPES, "group")), "PROCESSING_FAILED"); }
        finally { jdbc.execute("ALTER TABLE content_bank DROP CHECK import_test_failure"); }
        assertNoBusinessData();
    }

    @Test
    void uploadNeedsNoApprovalButPracticeImportWaitsForApprovalAndAvailabilityTracksRevocations() throws Exception {
        String cookie = cookie();
        var accepted = rawUpload(cookie, archive(List.of("单选题"), "bank"));
        assertThat(accepted.statusCode()).isEqualTo(202);
        var receipt = body(accepted);
        imports.validate(new ImportUploadService.Accepted(receipt.path("packId").asString(), 1));
        String bank = jdbc.queryForObject("SELECT bank_id FROM catalog_pack_bank WHERE pack_id=?", String.class, receipt.path("packId").asString());
        assertThat(availability.isAvailable(bank)).isFalse();
        assertError(create(cookie, receipt), 409, "PACK_NOT_APPROVED");
        imports.approve(receipt.path("packId").asString());
        assertThat(create(cookie, receipt).statusCode()).isEqualTo(200);
        assertThat(availability.isAvailable(bank)).isTrue();
        for (String status : List.of("revoked", "pending", "rejected")) {
            jdbc.update("UPDATE admin_extension_review SET status=?", status);
            assertThat(availability.isAvailable(bank)).isFalse();
        }
        assertThat(availability.isAvailable(Ids.uuidV7())).isFalse();
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void stoppedRealRunnerReturns503AndNoBusinessRows() throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(RUNNER.getContainerId()).exec();
        // This final test owns shutdown. Docker Desktop may change an ephemeral port on restart.
        assertBackgroundFailure(rawUpload(cookie(), archive(List.of("单选题"), "bank")), "RULE_UNAVAILABLE");
        assertNoBusinessData();
    }

    @Test
    void oversizedRawChunkedUploadAndServiceStreamsAreCountedWithoutLargeHeapBuffers() throws Exception {
        var response = http.send(HttpRequest.newBuilder(url()).timeout(Duration.ofSeconds(60)).header("Cookie", cookie()).header("Origin", ORIGIN)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> zeros(ImportService.MAX_UPLOAD_BYTES + 1024 * 1024)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 413, "LIBRARY_ARCHIVE_LIMIT");
        assertThatThrownBy(() -> uploads.receivePrivate(userId(), null, "big.qbank", -1, zeros(ImportService.MAX_UPLOAD_BYTES + 1)))
                .isInstanceOf(cloud.treenewbee.quizforge.shared.web.ApiException.class)
                .extracting(e -> ((cloud.treenewbee.quizforge.shared.web.ApiException) e).code()).isEqualTo("LIBRARY_ARCHIVE_LIMIT");
        assertNoBusinessData();
    }

    @Test
    void concurrentUploadsReturnBeforeAnyRulesRunAndCreateIndependentQueuedEntries() throws Exception {
        byte[] archive = archive(List.of("单选题"), "bank");
        String cookie = cookie();
        CountDownLatch gate = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> { gate.await(); return rawUpload(cookie, archive); });
            var second = threads.submit(() -> { gate.await(); return rawUpload(cookie, archive); });
            gate.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
            assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        }
        assertThat(operations).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_import_job WHERE status='queued'", Integer.class)).isEqualTo(2);
        assertNoBusinessData();
    }

    @Test
    void dailyUploadLimitIsPerUserAndResetsOnTheNextBeijingDay() throws Exception {
        String user = cookie();
        for (int i=0; i<20; i++) assertThat(rawUpload(user, new byte[]{1}).statusCode()).isEqualTo(202);
        assertError(rawUpload(user, new byte[]{1}), 429, "UPLOAD_LIMIT");
        assertThat(rawUpload(cookie(), new byte[]{1}).statusCode()).isEqualTo(202);
        clock.now.updateAndGet(now -> now.plus(Duration.ofDays(1)));
        assertThat(rawUpload(user, new byte[]{1}).statusCode()).isEqualTo(202);
        assertNoBusinessData();
    }

    @Test
    void originAndAuthenticationAreEnforcedBeforeUploadParsing() throws Exception {
        String cookie = cookie();
        for (String origin : List.of("", "https://evil.test")) {
            var builder = HttpRequest.newBuilder(url()).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString("not multipart"));
            if (!origin.isEmpty()) builder.header("Origin", origin);
            assertError(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()), 403, "ORIGIN_REJECTED");
        }
        assertError(http.send(HttpRequest.newBuilder(url()).header("Origin", ORIGIN).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()), 401, "UNAUTHENTICATED");
        assertNoBusinessData();
    }

    @Test
    @SuppressWarnings("unchecked")
    void centralOutlineControlsPositionsGroupsWarningsReadVisibilityAndTotalScore() throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "bank");
        ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> {
            Map<String,Object> original = (Map<String,Object>) ((List<?>)bank.get("questions")).getFirst();
            List<Map<String,Object>> all = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                Map<String,Object> question = JSON.readValue(JSON.writeValueAsBytes(original), LinkedHashMap.class);
                question.put("id", "q"+i); ((Map<String,Object>)question.get("data")).put("maxScore", i); all.add(question);
            }
            bank.put("questions", all);
            bank.put("outline", List.of(
                    Map.of("group", "作者分组", "items", List.of(Map.of("question", "q3", "label", "第三题先做"))),
                    Map.of("group", "作者分组", "items", List.of(Map.of("question", "q1", "parts", List.of(Map.of("id", "stable", "label", "小题"))))),
                    Map.of("items", List.of(Map.of("question", "q2")))));
        });
        // Explicit parts require the outline-items declaration.
        ImportArchives.edit(files, ImportArchives.extension("单选题") + "/manifest.json", manifest ->
                manifest.put("requiresApi", Map.of("major", 1, "minMinor", 1, "capabilities", List.of("outline-items"))));
        String cookie = cookie(); var response = upload(cookie, ImportArchives.zip(files));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode imported = body(response).path("collections").get(0);
        assertThat(jdbc.queryForList("SELECT question_id FROM content_question ORDER BY position", String.class)).containsExactly("q3", "q1", "q2");
        assertThat(jdbc.queryForList("SELECT outline_group_index FROM content_question ORDER BY position", Integer.class)).containsExactly(0, 1, 2);
        assertThat(jdbc.queryForList("SELECT outline_group_name FROM content_question ORDER BY position", String.class)).containsExactly("作者分组", "作者分组", null);
        assertThat(jdbc.queryForObject("SELECT question_count FROM content_bank", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(max_score) FROM content_question", java.math.BigDecimal.class)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT outline->>'$.level' FROM content_question WHERE question_id='q3'", String.class)).isEqualTo("question");
        assertThat(jdbc.queryForObject("SELECT outline->>'$.level' FROM content_question WHERE question_id='q1'", String.class)).isEqualTo("parts");
        jdbc.update("UPDATE admin_extension_review SET status='approved'");
        String path = "/api/collections/bank/" + imported.path("id").asString();
        var overview = get(cookie, path); assertThat(overview.statusCode()).as(overview.body()).isEqualTo(200);
        JsonNode rows = body(overview).path("questions");
        assertThat(rows.size()).isEqualTo(3);
        for(int i=0;i<3;i++) assertThat(rows.get(i).path("outlineGroupIndex").asInt()).isEqualTo(i);
        assertThat(rows.get(0).path("outlineGroupName").asString()).isEqualTo("作者分组");
        assertThat(body(overview).path("states").has("q4")).isFalse();
        assertThat(get(cookie, path+"/questions/q4").statusCode()).isEqualTo(404);
        var question=get(cookie,path+"/questions/q3");assertThat(question.statusCode()).as(question.body()).isEqualTo(200);
        assertThat(body(question).path("question").path("outlineGroupIndex").asInt()).isZero();
        assertThat(body(question).path("question").path("outlineGroupName").asString()).isEqualTo("作者分组");
        assertThat(overview.body()).doesNotContain("correctOptionId");assertThat(question.body()).doesNotContain("correctOptionId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupingAndOrderingChangeBankHashButNotQuestionHashAndUnusedContentStillChangesBankHash() throws Exception {
        var files=ImportArchives.files(List.of("单选题"),"bank");
        ImportArchives.edit(files,ImportArchives.bank("单选题")+"/bank.json",bank->{
            var first=(Map<String,Object>)((List<?>)bank.get("questions")).getFirst();
            var second=new LinkedHashMap<>(first);second.put("id","q2");
            var unused=new LinkedHashMap<>(first);unused.put("id","unused");
            bank.put("questions",List.of(first,second,unused));
            bank.put("outline",List.of(Map.of("group","旧分组","items",List.of(Map.of("question","q1"),Map.of("question","q2")))));
        });
        var first=imports.importArchive(auth.register(Ids.uuidV7()+"@example.test","用户","pass12345",null).userId(),"one.qbank",new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        ImportArchives.edit(files,ImportArchives.bank("单选题")+"/bank.json",bank->bank.put("outline",List.of(
                Map.of("group","新分组","items",List.of(Map.of("question","q2"))),Map.of("items",List.of(Map.of("question","q1"))))));
        var second=imports.importArchive(auth.register(Ids.uuidV7()+"@example.test","用户","pass12345",null).userId(),"two.qbank",new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        assertThat(first.bankId()).isNotEqualTo(second.bankId());
        for(String q:List.of("q1","q2")) assertThat(jdbc.queryForList("SELECT DISTINCT HEX(content_sha256) FROM content_question WHERE question_id=?",String.class,q)).hasSize(1);
        ImportArchives.edit(files,ImportArchives.bank("单选题")+"/bank.json",bank->((Map<String,Object>)((List<?>)bank.get("questions")).get(2)).put("title","未引用题也参与哈希"));
        var third=imports.importArchive(auth.register(Ids.uuidV7()+"@example.test","用户","pass12345",null).userId(),"three.qbank",new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        assertThat(third.bankId()).isNotEqualTo(second.bankId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_question WHERE question_id='unused'",Integer.class)).isZero();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"schema","rule","score","outline"})
    @SuppressWarnings("unchecked")
    void invalidUnreferencedQuestionStillRejectsEntireImport(String failure) throws Exception {
        var files=ImportArchives.files(List.of("单选题"),"bank");
        ImportArchives.edit(files,ImportArchives.bank("单选题")+"/bank.json",bank->{
            var first=(Map<String,Object>)((List<?>)bank.get("questions")).getFirst();
            Map<String,Object> unused=JSON.readValue(JSON.writeValueAsBytes(first),LinkedHashMap.class);unused.put("id","unused");
            if(failure.equals("schema"))unused.put("data",Map.of());
            else ((Map<String,Object>)unused.get("data")).put("maxScore",99);
            bank.put("questions",List.of(first,unused));
        });
        if(failure.equals("outline")) ImportArchives.edit(files,ImportArchives.extension("单选题")+"/manifest.json",m->m.put("requiresApi",Map.of("major",1,"minMinor",1,"capabilities",List.of("outline-items"))));
        String path=ImportArchives.extension("单选题")+"/rules.js";
        String hook=switch(failure) {
            case "rule" -> "validateQuestion(data){return data.maxScore!==99;},";
            case "score" -> "getScore(data){return {score:0,maxScore:data.maxScore===99?0.00001:1,gradingStatus:'graded'};},";
            case "outline" -> "getOutlineItems(data){return data.maxScore===99?[{id:'a',label:'1'},{id:'a',label:'2'}]:[];},";
            default -> "";
        };
        if(!hook.isEmpty())files.put(path,("QF.defineType({"+hook+"project(){return {maxScore:1}},grade(){return {}}})").getBytes(StandardCharsets.UTF_8));
        var response=rawUpload(cookie(),ImportArchives.zip(files));
        assertBackgroundFailure(response, switch (failure) {
            case "score" -> "SCORE_UNAVAILABLE";
            case "outline" -> "INVALID_OUTLINE_ITEMS";
            default -> "RULE_REJECTED";
        });
        assertNoBusinessData();
    }

    @Test
    @SuppressWarnings("unchecked")
    void changingOnlyUnreferencedQuestionsExtensionChangesBankIdentity() throws Exception {
        var files = ImportArchives.files(List.of("单选题", "判断题"), "bank");
        String visiblePath=ImportArchives.bank("单选题")+"/bank.json";
        Map<String,Object> unusedBank=JSON.readValue(files.get(ImportArchives.bank("判断题")+"/bank.json"),LinkedHashMap.class);
        Map<String,Object> unused=(Map<String,Object>)((List<?>)unusedBank.get("questions")).getFirst();
        unused.put("id","unused");unused.put("extension",Map.of("path",ImportArchives.extension("判断题")));
        files.keySet().removeIf(path->path.startsWith(ImportArchives.bank("判断题")+"/"));
        ImportArchives.edit(files,visiblePath,bank->bank.put("questions",List.of(((List<?>)bank.get("questions")).getFirst(),unused)));
        byte[] originalBankJson=files.get(visiblePath).clone();
        var first=imports.importArchive(userId(),"one.qbank",new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        String rulePath=ImportArchives.extension("判断题")+"/rules.js";
        files.put(rulePath,(new String(files.get(rulePath),StandardCharsets.UTF_8)+"\n// unreferenced extension revision\n").getBytes(StandardCharsets.UTF_8));
        var second=imports.importArchive(userId(),"two.qbank",new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        assertThat(files.get(visiblePath)).isEqualTo(originalBankJson);
        assertThat(second.bankId()).isNotEqualTo(first.bankId());
        assertThat(jdbc.queryForList("SELECT DISTINCT HEX(content_sha256) FROM content_question",String.class)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_question WHERE question_id='unused'",Integer.class)).isZero();
    }

    private HttpResponse<String> get(String cookie,String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    private String userId() { return auth.register(Ids.uuidV7()+"@example.test", "导入测试", "pass12345", null).userId(); }
    private String cookie() { return "__Host-qf_session=" + auth.register(Ids.uuidV7()+"@example.test", "导入测试", "pass12345", null).token(); }
    private URI url() { return URI.create("http://127.0.0.1:" + port + "/api/library/uploads?filename=test.qbank"); }
    private byte[] archive(List<String> types, String scope) throws Exception { return ImportArchives.zip(ImportArchives.files(types, scope)); }

    private HttpResponse<String> rawUpload(String cookie, byte[] archive) throws Exception {
        return http.send(HttpRequest.newBuilder(url()).timeout(Duration.ofSeconds(120)).header("Origin", ORIGIN).header("Cookie", cookie)
                .header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(archive)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String cookie, byte[] archive) throws Exception {
        var response = rawUpload(cookie, archive);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        var accepted = body(response);
        String pack = accepted.path("packId").asString();
        imports.validate(new ImportUploadService.Accepted(pack, accepted.path("revision").asInt()));
        imports.approve(pack);
        return create(cookie, accepted);
    }

    private HttpResponse<String> create(String cookie, JsonNode accepted) throws Exception {
        String pack = accepted.path("packId").asString();
        var banks = jdbc.queryForList("SELECT bank_uuid FROM catalog_pack_bank WHERE pack_id=? AND revision=? ORDER BY position", String.class,
                pack, accepted.path("revision").asInt());
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/library/packs/" + pack + "/practice"))
                .header("Origin", ORIGIN).header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("revision", accepted.path("revision").asInt(), "banks", banks))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertBackgroundFailure(HttpResponse<String> accepted, String code) {
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        String pack = body(accepted).path("packId").asString();
        assertThat(queue.runOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM content_import_job WHERE pack_id=?", String.class, pack)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT validation_status FROM catalog_pack_revision WHERE pack_id=?", String.class, pack))
                .isEqualTo(List.of("PROCESSING_FAILED", "RULE_UNAVAILABLE", "RULE_TIMEOUT").contains(code) ? "queued" : "rejected");
    }

    private static InputStream zeros(long size) {
        return new InputStream() {
            long remaining = size;
            @Override public int read() { if (remaining == 0) return -1; remaining--; return 0; }
            @Override public int read(byte[] bytes, int offset, int length) { if (remaining == 0) return -1; int count = (int)Math.min(length, remaining); java.util.Arrays.fill(bytes, offset, offset+count, (byte)0); remaining-=count; return count; }
        };
    }
    private JsonNode body(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(body(response).path("error").path("code").asString()).isEqualTo(code);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertNoBusinessData() { for(String table : tables()) assertThat(count(table)).as(table).isZero(); }
    private void clearBusiness() {
        for (String table : List.of("content_import_job", "content_validation_cache", "catalog_pack_bank", "catalog_pack_file", "catalog_pack_event", "catalog_pack_revision", "catalog_pack"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE content_import_slot SET job_id=NULL,lease_until=NULL");
        for (String table : tables()) jdbc.update("DELETE FROM " + table);
    }
    private List<String> tables() { return List.of("practice_collection", "content_question", "content_bank_asset", "content_bank", "content_extension_file", "content_extension_package", "admin_extension_review"); }
    private static Path temporary() { try { return Files.createTempDirectory("qf-import-test-").toRealPath(); } catch(Exception error) { throw new ExceptionInInitializerError(error); } }
    private static Path directory(String name) { try { return Files.createDirectories(ROOT.resolve(name)); } catch(Exception error) { throw new ExceptionInInitializerError(error); } }

    @TestConfiguration
    static class TestClockConfig { @Bean @Primary MutableClock importClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-29T12:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
