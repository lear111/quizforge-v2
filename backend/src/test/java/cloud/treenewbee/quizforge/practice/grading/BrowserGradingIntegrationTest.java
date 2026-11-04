package cloud.treenewbee.quizforge.practice.grading;

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
@TestPropertySource(properties="qf.grading.mode=browser")
@Import(BrowserGradingIntegrationTest.TimeConfig.class)
class BrowserGradingIntegrationTest extends MySqlTestBase {
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
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
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
        reset(runner); clock.now = Instant.parse("2026-11-03T12:00:00Z");
        var account = auth.register(Ids.uuidV7()+"@example.test", "浏览器判分", "password123", "test");
        user = account.userId(); cookie = "__Host-qf_session="+account.token(); viewers.add(user);
        samples.clear();
        seeds.forEach((name, seed) -> {
            String id = Ids.uuidV7();
            jdbc.update("INSERT INTO practice_collection(id,user_id,bank_id,title,source,created_at) SELECT ?,?,bank_id,title,source,created_at FROM practice_collection WHERE id=?",
                    id, user, seed.collection());
            samples.put(name, new Sample(id, seed.question()));
        });
    }

    @AfterEach void cleanup() {
        reset(runner);
        for (String viewer : viewers) deleteUser(viewer);
        viewers.clear();
        for (String bank : extraBanks) {jdbc.update("DELETE FROM content_question WHERE bank_id=?",bank);jdbc.update("DELETE FROM content_bank WHERE id=?",bank);}
        extraBanks.clear();
    }
    @AfterAll void close() throws Exception {
        deleteUser(seedUser); RUNNER.stop(); http.close();
        try (var paths = Files.walk(ROOT)) {for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
    }
    void deleteUser(String id) {
        jdbc.update("DELETE a FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id WHERE r.user_id=?",id);
        for (String table : List.of("practice_lifecycle_job","practice_history_annotation","practice_round_question","practice_round",
                "practice_question_state","practice_whiteboard","practice_context","exam_exam","practice_collection","practice_request_receipt","auth_session"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id=?",id);
        jdbc.update("DELETE FROM account_user WHERE id=?",id);
    }

    @ParameterizedTest @ValueSource(strings={"单选题","多选题","判断题","简答题","简答题（答题区）"})
    void locksBeforeReturningRawAndStoresTheSameOutcomeAsRealRunner(String type) throws Exception {
        var sample = samples.get(type); var answer = answer(type);
        var expected = grader.grade(user,sample.collection(),sample.question().questionId(),answer);
        clearInvocations(runner);
        var locked = submit(type,"first",0,answer);
        assertThat(locked.path("state").path("result").path("gradingStatus").asString()).isEqualTo("awaiting_browser");
        assertThat(locked.path("browserGrading").path("data")).isEqualTo(JSON.readTree(sample.question().data()));
        assertThat(locked.path("browserGrading").path("source").asString()).contains("QF.defineType");
        assertThat(submit(type,"first",0,answer)).isEqualTo(locked);
        var report = outcome(expected);
        var saved = service.report(user,sample.collection(),sample.question().questionId(),generation(locked),report);
        assertThat(saved.path("state").path("result").path("gradingStatus").asString()).isEqualTo(expected.gradingStatus());
        if (expected.score()==null) assertThat(saved.path("state").path("result").path("score").isNull()).isTrue();
        else assertThat(new java.math.BigDecimal(saved.path("state").path("result").path("score").toString())).isEqualByComparingTo(expected.score());
        assertThat(service.report(user,sample.collection(),sample.question().questionId(),generation(locked),report)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("browser");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(3);
        verify(runner,never()).execute(anyString(),any(),anyMap());
    }

    @Test void rawDataIsUnavailableBeforeLockAcrossQuestionsAndAcrossUsers() throws Exception {
        var sample = samples.get("单选题");
        var before = (PracticeViews.QuestionResponse) reads.question(user,sample.collection(),sample.question().questionId(),null);
        assertThat(before.question().data().has("correctOptionId")).isFalse();
        clearInvocations(runner);
        error(get(base(sample)+"/grading/pending?questionId="+sample.question().questionId()),409,"GRADING_GENERATION_CONFLICT");
        var locked = submit("单选题","lock",0,answer("单选题"));
        assertThat(ok(get(base(sample)+"/grading/pending?questionId="+sample.question().questionId())).path("items").get(0).path("data").has("correctOptionId")).isTrue();
        var otherQuestion = samples.get("多选题");
        error(get(base(otherQuestion)+"/grading/pending?questionId="+otherQuestion.question().questionId()),409,"GRADING_GENERATION_CONFLICT");
        var other = auth.register(Ids.uuidV7()+"@example.test","他人","password123","test");viewers.add(other.userId());
        String oldCookie = cookie; cookie = "__Host-qf_session="+other.token();
        try {
            error(get(base(sample)+"/grading/pending"),404,"NOT_FOUND");
            error(post(base(sample)+"/grading/reports",batch(sample,generation(locked),simpleOutcome())),404,"NOT_FOUND");
        } finally {cookie=oldCookie;}
        verify(runner,never()).execute(anyString(),any(),anyMap());
    }

    @Test void invalidReportsDoNotConsumeGenerationAndStaleRetryCannotOverwrite() throws Exception {
        var sample=samples.get("单选题");var locked=submit("单选题","lock",0,answer("单选题"));
        var bad=(ObjectNode)simpleOutcome();((ObjectNode)bad.path("score")).put("score",-1);
        error(post(base(sample)+"/grading/reports",batch(sample,generation(locked),bad)),400,"INVALID_GRADING_REPORT");
        var saved=service.report(user,sample.collection(),sample.question().questionId(),generation(locked),simpleOutcome());
        var retry=post(base(sample)+"/questions/"+sample.question().questionId()+"/actions",JSON.valueToTree(Map.of(
                "requestId","retry","revision",saved.path("revision").asLong(),"action","retry","data",Map.of())));
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        error(post(base(sample)+"/grading/reports",batch(sample,generation(locked),simpleOutcome())),409,"GRADING_GENERATION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT status FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("unanswered");
    }

    @Test void twoConcurrentIdenticalSubmitsAndReportsAreIdempotent() throws Exception {
        var sample=samples.get("单选题");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return submit("单选题","same",0,answer("单选题"));});
            var b=pool.submit(()->{start.await();return submit("单选题","same",0,answer("单选题"));});
            start.countDown();var locked=a.get(30,TimeUnit.SECONDS);assertThat(b.get(30,TimeUnit.SECONDS)).isEqualTo(locked);
            var reports=new CountDownLatch(1);
            var c=pool.submit(()->{reports.await();return service.report(user,sample.collection(),sample.question().questionId(),generation(locked),simpleOutcome());});
            var d=pool.submit(()->{reports.await();return service.report(user,sample.collection(),sample.question().questionId(),generation(locked),simpleOutcome());});
            reports.countDown();assertThat(c.get(30,TimeUnit.SECONDS)).isEqualTo(d.get(30,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(3);
        verify(runner,never()).execute(anyString(),any(),anyMap());
    }

    @Test void expiredWaitingSubmissionUsesExistingLowPriorityWorker() throws Exception {
        var sample=samples.get("单选题");submit("单选题","lock",0,answer("单选题"));
        assertThat(jobs.tick()).isFalse();verify(runner,never()).execute(anyString(),any(),anyMap());
        clock.now=clock.now.plusSeconds(121);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return call.callRealMethod();})
                .when(runner).execute(eq("submit"),any(),anyMap());
        assertThat(jobs.tick()).isTrue();
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("server");
        assertThat(jdbc.queryForObject("SELECT grading_status FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("graded");
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
    }

    @Test void finishLocksSavedAnswersAndCompletesAfterBrowserBatchWithoutRunner() throws Exception {
        var sample=twoQuestionBank();
        for(String qid:List.of(sample.question().questionId(),"second"))
            drafts.save(user,sample.collection(),qid,Ids.uuidV7(),0,answer("单选题"),null);
        clearInvocations(runner);
        var summary=summaries.summary(user,sample.collection());
        var accepted=jobs.begin(user,sample.collection(),"finish",null,summary.path("summaryVersion").asString(),"finish");
        var pending=service.pending(user,sample.collection(),null).path("items");assertThat(pending.size()).isEqualTo(2);
        assertThat(jobs.tick()).isFalse();
        for(var ticket:pending) service.report(user,sample.collection(),ticket.path("questionId").asString(),ticket.path("generation").asString(),simpleOutcome());
        assertThat(jobs.tick()).isTrue();
        assertThat(jobs.progress(user,sample.collection(),accepted.path("jobId").asString()).path("status").asString()).isEqualTo("completed");
        assertThat(summaries.summary(user,sample.collection()).path("finished").asBoolean()).isTrue();
        verify(runner,never()).execute(anyString(),any(),anyMap());
    }

    @Test void examManualBatchDoesNotCallRunnerButClosedPageDeadlineDoes() throws Exception {
        var sample=samples.get("单选题");
        var exam=exams.start(user,sample.collection(),"exam",1);String id=exam.path("id").asString();
        exams.write(user,id,()->drafts.save(user,id,sample.question().questionId(),"draft",0,answer("单选题"),null));
        clearInvocations(runner);exams.submit(user,id,"submit");
        var ticket=service.pending(user,id,null).path("items").get(0);
        service.report(user,id,ticket.path("questionId").asString(),ticket.path("generation").asString(),simpleOutcome());
        for(int n=0;n<5 && !exams.owned(user,id).status().equals("completed");n++) jobs.tick();
        assertThat(exams.owned(user,id).status()).isEqualTo("completed");verify(runner,never()).execute(anyString(),any(),anyMap());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state s JOIN practice_context c ON c.id=s.context_id WHERE c.user_id=? AND c.kind='practice'",Integer.class,user)).isZero();
        var expired=exams.start(user,sample.collection(),"closed",1);String closed=expired.path("id").asString();
        exams.write(user,closed,()->drafts.save(user,closed,sample.question().questionId(),"closed-draft",0,answer("单选题"),null));
        clock.now=clock.now.plusSeconds(61);scanner.scan();jobs.tick();
        verify(runner,times(1)).execute(eq("submit"),any(),anyMap());
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE context_id=?",String.class,
                exams.owned(user,closed).contextId())).isEqualTo("server");
    }

    @Test void manualReviewOverridesBrowserResultAndFencesOldReports() throws Exception {
        var sample=samples.get("简答题");var locked=submit("简答题","lock",0,answer("简答题"));
        var expected=grader.grade(user,sample.collection(),sample.question().questionId(),answer("简答题"));
        var saved=service.report(user,sample.collection(),sample.question().questionId(),generation(locked),outcome(expected));
        String part=JSON.readTree(sample.question().data()).path("parts").get(0).path("id").asString();
        var body=JSON.valueToTree(Map.of("requestId","review","revision",saved.path("revision").asLong(),"action","review",
                "data",Map.of("review",Map.of("score",5,"targetId",part))));
        assertThat(post(base(sample)+"/questions/"+sample.question().questionId()+"/actions",body).statusCode()).isEqualTo(200);
        error(post(base(sample)+"/grading/reports",batch(sample,generation(locked),outcome(expected))),409,"GRADING_GENERATION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("manual");
    }

    @Test void browserReportWinsAgainstAlreadyRunningFallbackWithoutBeingOverwritten() throws Exception {
        var sample=samples.get("单选题");var locked=submit("单选题","lock",0,answer("单选题"));
        clock.now=clock.now.plusSeconds(121);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(30,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();})
                .when(runner).execute(eq("submit"),any(),anyMap());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var fallback=pool.submit(()->jobs.tick());
            try {
                assertThat(entered.await(30,TimeUnit.SECONDS)).isTrue();
                service.report(user,sample.collection(),sample.question().questionId(),generation(locked),simpleOutcome());
            } finally {release.countDown();}
            assertThat(fallback.get(30,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT grading_source FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("browser");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE user_id=?",Long.class,user)).isEqualTo(3);
    }

    @Test void rejectsMalformedOversizedAndOverfullBatchesWithoutChangingSubmission() throws Exception {
        var sample=samples.get("单选题");var locked=submit("单选题","lock",0,answer("单选题"));
        error(post(base(sample)+"/grading/reports",JSON.createObjectNode()),400,"INVALID_GRADING_REPORT");
        var item=batch(sample,generation(locked),simpleOutcome()).path("items").get(0);
        var many=JSON.createArrayNode();for(int n=0;n<21;n++)many.add(item);
        error(post(base(sample)+"/grading/reports",JSON.createObjectNode().set("items",many)),400,"INVALID_GRADING_REPORT");
        error(post(base(sample)+"/grading/reports",JSON.createObjectNode().put("large","x".repeat(8*1024*1024))),413,"GRADING_REPORT_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT grading_status FROM practice_question_state WHERE user_id=?",String.class,user)).isEqualTo("awaiting_browser");
        verify(runner,never()).execute(anyString(),any(),anyMap());
    }

    @Test void revokedExtensionCannotBeSentToTheBrowser() throws Exception {
        var sample=samples.get("单选题");submit("单选题","lock",0,answer("单选题"));
        try {
            jdbc.update("UPDATE admin_extension_review SET status='revoked' WHERE digest=?",sample.question().extensionDigest());
            error(get(base(sample)+"/grading/pending"),422,"CLOUD_EXTENSION_REVIEW_REQUIRED");
        } finally {jdbc.update("UPDATE admin_extension_review SET status='approved' WHERE digest=?",sample.question().extensionDigest());}
        verify(runner,never()).execute(anyString(),any(),anyMap());
    }

    Sample twoQuestionBank() {
        var seed=samples.get("单选题");var old=banks.findById(seed.question().bankId()).orElseThrow();String id=Ids.uuidV7();
        banks.insert(new BankRow(id,java.util.HexFormat.of().parseHex(Ids.uuidV7().replace("-","")+Ids.uuidV7().replace("-","")),
                Ids.uuidV7(),old.sourceTitle(),old.description(),old.formatVersion(),old.features(),old.listening(),2,clock.instant()));
        extraBanks.add(id);var q=seed.question();
        for(var entry:Map.of(q.questionId(),0,"second",1).entrySet()) questions.insert(new QuestionRow(id,entry.getKey(),entry.getValue(),q.title(),q.extensionDigest(),q.outline(),q.listening(),q.data(),q.maxScore(),q.contentSha256(),q.outlineGroupIndex(),q.outlineGroupName()));
        jdbc.update("UPDATE practice_collection SET bank_id=? WHERE id=? AND user_id=?",id,seed.collection(),user);
        return new Sample(seed.collection(),questions.findByPosition(id,0).orElseThrow());
    }
    JsonNode submit(String type,String request,long revision,JsonNode answer) throws Exception {
        var sample=samples.get(type);
        return ok(post(base(sample)+"/questions/"+sample.question().questionId()+"/actions",JSON.valueToTree(Map.of(
                "requestId",request,"revision",revision,"action","submit","data",Map.of("answer",answer)))));
    }
    JsonNode outcome(GradeOutcome value) {
        var result=JSON.createObjectNode();result.set("result",JSON.readTree(value.resultJson()));result.set("projected",JSON.readTree(value.projectedJson()));
        var score=result.putObject("score");score.put("score",value.score()).put("maxScore",value.maxScore()).put("gradingStatus",value.gradingStatus());
        if(value.outlineStatesJson()!=null)score.set("outlineStates",JSON.readTree(value.outlineStatesJson()));return result;
    }
    JsonNode simpleOutcome() {return JSON.readTree("{\"result\":{\"score\":1,\"maxScore\":1,\"correct\":true,\"feedback\":\"浏览器\"},\"projected\":{\"browser\":true},\"score\":{\"score\":1,\"maxScore\":1,\"gradingStatus\":\"graded\"}}");}
    JsonNode batch(Sample sample,String generation,JsonNode outcome) {return JSON.valueToTree(Map.of("items",List.of(Map.of("questionId",sample.question().questionId(),"generation",generation,"outcome",outcome))));}
    String generation(JsonNode locked) {return locked.path("browserGrading").path("generation").asString();}
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
    static Path temp() {try{return Files.createTempDirectory("qf-browser-grading-");}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    static Path dir(String name) {try{return Files.createDirectory(ROOT.resolve(name));}catch(Exception error){throw new ExceptionInInitializerError(error);}}
}
