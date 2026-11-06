package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.catalog.application.CatalogReviewService;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.testsupport.QueuedPracticeImports;
import cloud.treenewbee.quizforge.practice.application.PracticeLifecycleJobs;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 题库广场 3/4：广场题库实例的版本自动切换、更新通知与“作者已移除 / 已删除”（设计 04 第 3 节）。
 * 内容由真实的 rule-runner 导入（与生产相同的导入代码）；两个版本中“判断题”的 UUID 相同、内容不同，
 * 第 2 版去掉了“单选题”。
 */
@Timeout(300)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlazaUpdatesIntegrationTest extends MySqlTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TOKEN = "plaza-updates-test";
    static final String ORIGIN = "https://plaza-updates.example.test";
    static final Path ROOT = temporary(), BLOBS = directory("blobs"), UPLOADS = directory("uploads");
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
            .withEnv("QF_RULE_WORKER_LIMIT", "2").withFileSystemBind(BLOBS.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN))
            .withStartupTimeout(Duration.ofMinutes(2));
    static { RUNNER.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.practice.jobs.enabled", () -> "false");
        registry.add("qf.security.allowed-origin", () -> ORIGIN);
        registry.add("qf.storage.blob-dir", BLOBS::toString);
        registry.add("qf.import.temp-dir", UPLOADS::toString);
        registry.add("qf.rule-runner.base-url", () -> "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090));
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired AuthService auth;
    @Autowired QueuedPracticeImports imports;
    @Autowired CatalogMapper catalog;
    @Autowired CatalogReviewService reviews;
    @Autowired PracticeLifecycleJobs jobs;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    record User(String id, String cookie) {
    }

    /** 第 2 版判断题中改了标题的题目 ID。 */
    static String changed;
    /** 第 3 版恢复的单选题中改了标题的题目 ID。 */
    static String restored;

    /** 两个版本中各题库的共享内容：UUID → content_bank.id，以及“判断题”的 UUID。 */
    final Map<String, String> v1 = new LinkedHashMap<>(), v2 = new LinkedHashMap<>(), v3 = new LinkedHashMap<>(),
            titles = new LinkedHashMap<>();
    String author, admin, judge, single;
    final List<String> learners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 其他测试类会对整张作答表加 CHECK 约束（如 DraftActionIntegrationTest），这里不留下作答行。 */
    @AfterAll
    void removeAnswers() {
        for (String learner : learners) jdbc.update("DELETE FROM practice_question_state WHERE user_id = ?", learner);
    }

    @BeforeAll
    void importBothVersions() throws Exception {
        // @BeforeAll 早于基类每个用例前的设置：先同样关闭注册验证，不依赖其他测试类先运行。
        jdbc.update("""
                UPDATE auth_policy SET login_code = FALSE, login_captcha = FALSE, registration_code = FALSE,
                       registration_captcha = FALSE, invitation_required = FALSE WHERE id = 1
                """);
        author = auth.register(Ids.uuidV7() + "@example.test", "作者", "password-123", null).userId();
        admin = auth.register(Ids.uuidV7() + "@example.test", "审核员", "password-123", null).userId();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", admin);
        byte[] original = Files.readAllBytes(Path.of(System.getProperty("basedir", ".")).resolve("../samples/basic-types.qbank"));
        for (var row : imports.importArchive(author, "v1.qbank", new ByteArrayInputStream(original))) {
            String uuid = uuid(row.id());
            v1.put(uuid, row.bankId());
            titles.put(row.title(), uuid);
        }
        judge = titles.get("判断题");
        single = titles.get("单选题");
        for (var row : imports.importArchive(author, "v2.qbank", new ByteArrayInputStream(secondVersion(original)))) {
            v2.put(uuid(row.id()), row.bankId());
        }
        assertThat(v2).containsKey(judge).doesNotContainKey(single);
        assertThat(v2.get(judge)).isNotEqualTo(v1.get(judge));
        for (var row : imports.importArchive(author, "v3.qbank", new ByteArrayInputStream(thirdVersion(original)))) {
            v3.put(uuid(row.id()), row.bankId());
        }
        assertThat(v3).containsKey(single);
        assertThat(v3.get(single)).isNotEqualTo(v1.get(single));
        jdbc.update("UPDATE admin_extension_review SET status = 'approved'");
    }

    // ------------------------------------------------------------------ 懒更新

    @Test
    void untouchedBanksSwitchOnTheNextOpenAndKeepTheirNameAndGroup() throws Exception {
        String pack = pack();
        User learner = user();
        String collection = importBank(learner, pack, judge);
        jdbc.update("UPDATE practice_collection SET title = '我的判断题', group_name = '我的分组' WHERE id = ?", collection);
        approveSecond(pack);

        // 列表只读：显示有新版本，不切换。
        assertThat(listUpdate(learner, collection)).isEqualTo("pending");
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));

        JsonNode overview = json(get(learner, "/api/collections/bank/" + collection));
        assertThat(overview.path("update").path("status").asString()).isEqualTo("current");
        assertThat(bankOf(collection)).isEqualTo(v2.get(judge));
        assertThat(title(overview, changed)).endsWith("（第二版）");
        assertThat(jdbc.queryForMap("SELECT title, group_name, source_revision, update_status FROM practice_collection WHERE id = ?",
                collection)).containsEntry("title", "我的判断题").containsEntry("group_name", "我的分组")
                .containsEntry("source_revision", 2).containsEntry("update_status", "current");
        assertThat(listUpdate(learner, collection)).isEqualTo("current");
    }

    @Test
    void answeringBanksStayUntilRestartWhichSwitchesInTheSameTransactionAndKeepsHistoryFrozen() throws Exception {
        String pack = pack();
        User learner = user();
        String collection = importBank(learner, pack, judge);
        JsonNode first = json(get(learner, "/api/collections/bank/" + collection));
        String qid = changed;
        String oldTitle = title(first, qid);
        ok(action(learner, collection, qid, "submit", 0, Map.of("value", true), null));
        approveSecond(pack);

        JsonNode pending = json(get(learner, "/api/collections/bank/" + collection));
        assertThat(pending.path("update").path("status").asString()).isEqualTo("pending");
        assertThat(pending.path("update").path("revision").asInt()).isEqualTo(2);
        assertThat(title(pending, qid)).isEqualTo(oldTitle);
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));

        // 完成本轮（已完成但未再练一次）仍不切换。
        ok(post(learner, "/api/collections/bank/" + collection + "/finish", lifecycleBody(summary(learner, collection))));
        assertThat(json(get(learner, "/api/collections/bank/" + collection)).path("update").path("status").asString()).isEqualTo("pending");
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));

        // 再练一次：清空作答并切换到第 2 版；冻结的历史仍是第 1 版的题目。
        JsonNode restarted = ok(post(learner, "/api/collections/bank/" + collection + "/restart", lifecycleBody(summary(learner, collection))));
        assertThat(restarted.path("restarted").asBoolean()).isTrue();
        assertThat(bankOf(collection)).isEqualTo(v2.get(judge));
        JsonNode now = json(get(learner, "/api/collections/bank/" + collection));
        assertThat(now.path("update").path("status").asString()).isEqualTo("current");
        assertThat(title(now, qid)).endsWith("（第二版）");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question q JOIN practice_round r ON r.id = q.round_id "
                + "WHERE r.collection_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(q.payload, '$.question.title')) = ?", Integer.class,
                collection, oldTitle)).isEqualTo(1);
        // 新一轮用新版本作答。
        ok(action(learner, collection, qid, "draft", revisionOf(collection, qid), Map.of("value", false), null));
    }

    @Test
    void versionsUnderReviewAreIgnoredAndOnlyApprovedVersionsCount() throws Exception {
        String pack = pack();
        User learner = user();
        String collection = importBank(learner, pack, judge);
        submitSecond(pack);
        assertThat(json(get(learner, "/api/collections/bank/" + collection)).path("update").path("status").asString()).isEqualTo("current");
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));
        assertThat(listUpdate(learner, collection)).isEqualTo("current");
    }

    @Test
    void examsInProgressBlockTheSwitchAndKeepTheirStartingVersion() throws Exception {
        String pack = pack();
        User learner = user();
        String collection = importBank(learner, pack, judge);
        String exam = json(post(learner, "/api/collections/bank/" + collection + "/exams",
                JSON.createObjectNode().put("requestId", Ids.uuidV7()).putNull("durationMinutes"))).path("id").asString();
        approveSecond(pack);

        assertThat(json(get(learner, "/api/collections/bank/" + collection)).path("update").path("status").asString()).isEqualTo("pending");
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));
        assertThat(title(json(get(learner, "/api/collections/exam/" + exam)), changed)).doesNotEndWith("（第二版）");

        jdbc.update("UPDATE exam_exam SET status = 'completed' WHERE id = ?", exam);
        assertThat(json(get(learner, "/api/collections/bank/" + collection)).path("update").path("status").asString()).isEqualTo("current");
        assertThat(bankOf(collection)).isEqualTo(v2.get(judge));
        // 开考时的版本已冻结在考试上。
        assertThat(jdbc.queryForObject("SELECT bank_id FROM exam_exam WHERE id = ?", String.class, exam)).isEqualTo(v1.get(judge));
    }

    @Test
    void removedBanksStopUpdatingAndDeletedPacksAreMarkedWhilePracticeStillWorks() throws Exception {
        String pack = pack();
        User learner = user();
        String removed = importBank(learner, pack, single);
        String kept = importBank(learner, pack, judge);
        approveSecond(pack);

        JsonNode overview = json(get(learner, "/api/collections/bank/" + removed));
        assertThat(overview.path("update").path("status").asString()).isEqualTo("removed");
        assertThat(bankOf(removed)).isEqualTo(v1.get(single));
        assertThat(jdbc.queryForObject("SELECT update_status FROM practice_collection WHERE id = ?", String.class, removed)).isEqualTo("removed");
        String qid = overview.path("questions").get(0).path("id").asString();
        assertThat(get(learner, "/api/collections/bank/" + removed + "/questions/" + qid).statusCode()).isEqualTo(200);

        jdbc.update("UPDATE catalog_pack SET status = 'deleted', deleted_at = NOW(3) WHERE id = ?", pack);
        assertThat(listUpdate(learner, removed)).isEqualTo("removed");
        assertThat(listUpdate(learner, kept)).isEqualTo("deleted");
        JsonNode deleted = json(get(learner, "/api/collections/bank/" + kept));
        assertThat(deleted.path("update").path("status").asString()).isEqualTo("deleted");
        assertThat(bankOf(kept)).isEqualTo(v1.get(judge));
        ok(action(learner, kept, changed, "draft", 0, Map.of("value", true), null));
    }

    // ------------------------------------------------------------------ 并发

    /** “作者已移除”不是粘性的（作者决定，2026-11-05）：之后的版本恢复同一 UUID 时按第 3.1 节切换。 */
    @Test
    void removedBanksResumeUpdatingWhenALaterVersionRestoresThem() throws Exception {
        String pack = pack();
        User learner = user(), idle = user();
        String answered = importBank(learner, pack, single);
        String untouched = importBank(idle, pack, single);
        String qid = restored;
        String correct = JSON.readTree(jdbc.queryForObject("SELECT data FROM content_question WHERE bank_id = ? AND question_id = ?",
                String.class, v1.get(single), qid)).path("correctOptionId").asString();
        String oldTitle = title(json(get(learner, "/api/collections/bank/" + answered)), qid);
        ok(action(learner, answered, qid, "submit", 0, Map.of("selectedOptionId", correct), null));
        ok(post(learner, "/api/collections/bank/" + answered + "/finish", lifecycleBody(summary(learner, answered))));

        // 第 2 版去掉单选题：两人都显示“作者已移除”，停在第 1 版；已完成的作答与本轮汇总不变。
        approveSecond(pack);
        assertThat(json(get(learner, "/api/collections/bank/" + answered)).path("update").path("status").asString()).isEqualTo("removed");
        assertThat(json(get(idle, "/api/collections/bank/" + untouched)).path("update").path("status").asString()).isEqualTo("removed");
        assertThat(bankOf(answered)).isEqualTo(v1.get(single));
        assertThat(bankOf(untouched)).isEqualTo(v1.get(single));
        assertThat(jdbc.queryForObject("SELECT s.status FROM practice_question_state s JOIN practice_context x ON x.id = s.context_id "
                + "WHERE x.collection_id = ? AND s.question_id = ?", String.class, answered, qid)).isEqualTo("submitted");
        assertThat(summary(learner, answered).path("canRestart").asBoolean()).isTrue();

        // 第 3 版恢复单选题：没有作答的用户打开即切换；有作答（已完成未再练一次）的用户看到“有新版本”。
        approveThird(pack);
        assertThat(listUpdate(idle, untouched)).isEqualTo("pending");
        JsonNode switched = json(get(idle, "/api/collections/bank/" + untouched));
        assertThat(switched.path("update").path("status").asString()).isEqualTo("current");
        assertThat(bankOf(untouched)).isEqualTo(v3.get(single));
        assertThat(title(switched, qid)).endsWith("（第三版）");
        assertThat(jdbc.queryForMap("SELECT source_revision, update_status FROM practice_collection WHERE id = ?", untouched))
                .containsEntry("source_revision", 3).containsEntry("update_status", "current");

        JsonNode pending = json(get(learner, "/api/collections/bank/" + answered));
        assertThat(pending.path("update").path("status").asString()).isEqualTo("pending");
        assertThat(pending.path("update").path("revision").asInt()).isEqualTo(3);
        assertThat(listUpdate(learner, answered)).isEqualTo("pending");
        assertThat(bankOf(answered)).isEqualTo(v1.get(single));
        assertThat(title(pending, qid)).isEqualTo(oldTitle);

        // 再练一次：切换到恢复后的第 3 版；冻结的历史仍是第 1 版的题目。
        JsonNode restarted = ok(post(learner, "/api/collections/bank/" + answered + "/restart", lifecycleBody(summary(learner, answered))));
        assertThat(restarted.path("restarted").asBoolean()).isTrue();
        assertThat(bankOf(answered)).isEqualTo(v3.get(single));
        JsonNode now = json(get(learner, "/api/collections/bank/" + answered));
        assertThat(now.path("update").path("status").asString()).isEqualTo("current");
        assertThat(title(now, qid)).endsWith("（第三版）");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question q JOIN practice_round r ON r.id = q.round_id "
                + "WHERE r.collection_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(q.payload, '$.question.title')) = ?", Integer.class,
                answered, oldTitle)).isEqualTo(1);
    }

    @Test
    void savesWithTheOldQuestionAfterASwitchAreRejectedAndConcurrentOpensSwitchOnce() throws Exception {
        String pack = pack();
        User learner = user();
        String collection = importBank(learner, pack, judge);
        json(get(learner, "/api/collections/bank/" + collection));
        String qid = changed;
        String oldVersion = json(get(learner, "/api/collections/bank/" + collection + "/questions/" + qid)).path("stamp")
                .path("contentVersion").asString();
        approveSecond(pack);

        // 两个页面同时打开：只切换一次（progress_version 只加一次）。
        long before = progressVersion(collection);
        List<Future<HttpResponse<String>>> opens = new ArrayList<>();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++) opens.add(pool.submit(() -> { gate.await(); return get(learner, "/api/collections/bank/" + collection); }));
            gate.countDown();
            for (var open : opens) assertThat(open.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(bankOf(collection)).isEqualTo(v2.get(judge));
        assertThat(progressVersion(collection)).isEqualTo(before + 1);

        // 页面还停在旧版本：拿着旧题目的保存被拒绝，要求刷新。
        HttpResponse<String> stale = action(learner, collection, qid, "draft", 0, Map.of("value", true), oldVersion);
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state s JOIN practice_context x ON x.id = s.context_id "
                + "WHERE x.collection_id = ? AND s.status IN ('draft', 'submitted')", Integer.class, collection)).isZero();
    }

    @Test
    void racingTheFirstSaveAgainstTheSwitchNeverMixesVersions() throws Exception {
        for (int round = 0; round < 8; round++) {
            String pack = pack();
            User learner = user();
            String collection = importBank(learner, pack, judge);
            json(get(learner, "/api/collections/bank/" + collection));
            String qid = changed;
            String oldVersion = json(get(learner, "/api/collections/bank/" + collection + "/questions/" + qid)).path("stamp")
                    .path("contentVersion").asString();
            approveSecond(pack);
            var gate = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var open = pool.submit(() -> { gate.await(); return get(learner, "/api/collections/bank/" + collection); });
                var save = pool.submit(() -> { gate.await(); return action(learner, collection, qid, "draft", 0, Map.of("value", true), oldVersion); });
                gate.countDown();
                assertThat(open.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                int saved = save.get(60, TimeUnit.SECONDS).statusCode();
                assertThat(saved).isIn(200, 409);
            }
            // 不变式：存档中任何作答的题目版本都属于题库实例当前指向的内容。
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM practice_question_state s JOIN practice_context x ON x.id = s.context_id
                      JOIN practice_collection c ON c.id = x.collection_id
                      LEFT JOIN content_question q ON q.bank_id = c.bank_id AND q.question_id = s.question_id
                                                  AND q.content_sha256 = s.content_sha256
                     WHERE c.id = ? AND s.status IN ('draft', 'submitted') AND q.question_id IS NULL
                    """, Integer.class, collection)).as("round " + round).isZero();
            // 保存赢了就不切换（有新版本）；切换赢了保存被拒绝。
            boolean switched = v2.get(judge).equals(bankOf(collection));
            int answers = jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state s JOIN practice_context x ON x.id = s.context_id "
                    + "WHERE x.collection_id = ? AND s.status = 'draft'", Integer.class, collection);
            assertThat(switched ? answers == 0 : answers == 1).as("round " + round).isTrue();
        }
    }

    @Test
    void finishingJobsBlockTheSwitch() throws Exception {
        String pack = pack();
        User learner = user();
        String collection = importBank(learner, pack, judge);
        json(get(learner, "/api/collections/bank/" + collection));
        ok(action(learner, collection, changed, "draft", 0, Map.of("value", true), null));
        // 完成练习任务已排队（尚未执行）：存档处于“正在完成”，不切换。
        HttpResponse<String> accepted = post(learner, "/api/collections/bank/" + collection + "/finish", lifecycleBody(summary(learner, collection)));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        jdbc.update("UPDATE practice_question_state s JOIN practice_context x ON x.id = s.context_id SET s.status = 'unanswered' "
                + "WHERE x.collection_id = ?", collection);
        approveSecond(pack);
        assertThat(json(get(learner, "/api/collections/bank/" + collection)).path("update").path("status").asString()).isEqualTo("pending");
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));
    }

    // ------------------------------------------------------------------ 通知与归属

    @Test
    void approvalNotifiesOnlyOwnersOfThePackWithTheReleaseNotes() throws Exception {
        String pack = pack();
        User learner = user();
        User former = user();
        User stranger = user();
        importBank(learner, pack, judge);
        String formerCollection = importBank(former, pack, judge);
        jdbc.update("UPDATE practice_collection SET deleted_at = NOW(3) WHERE id = ?", formerCollection);
        // 另一个包中有相同 UUID 的题库：不接收这个包的通知。
        importBank(stranger, pack(), judge);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE audience = 'bank' AND target_id = ?", Long.class, pack);
        assertThat(before).isZero();
        approveSecond(pack);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE audience = 'bank' AND target_id = ? AND source = 'system'",
                Long.class, pack)).isEqualTo(1);
        JsonNode items = json(get(learner, "/api/notifications")).path("items");
        String id = null;
        for (JsonNode item : items) if (item.path("title").asString().contains("已更新到第 2 版")) id = item.path("id").asString();
        assertThat(id).isNotNull();
        assertThat(json(get(learner, "/api/notifications/" + id)).path("body").asString()).contains("判断题第 1 题改了说法");
        for (User other : List.of(former, stranger)) {
            assertThat(json(get(other, "/api/notifications")).path("items").toString()).doesNotContain("已更新到第 2 版");
            assertThat(get(other, "/api/notifications/" + id).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void otherUsersCannotTriggerOrObserveAnotherUsersSwitch() throws Exception {
        String pack = pack();
        User owner = user();
        User intruder = user();
        String collection = importBank(owner, pack, judge);
        approveSecond(pack);
        assertThat(get(intruder, "/api/collections/bank/" + collection).statusCode()).isEqualTo(404);
        assertThat(bankOf(collection)).isEqualTo(v1.get(judge));
        assertThat(json(get(intruder, "/api/library/index")).path("banks").toString()).doesNotContain(collection);
        assertThat(send(intruder, "PUT", "/api/plaza/packs/" + pack + "/favorite/seen", null).statusCode()).isEqualTo(404);
    }

    @Test
    void favoritesShowTheNewVersionUntilAcknowledged() throws Exception {
        String pack = pack();
        User learner = user();
        assertThat(send(learner, "PUT", "/api/plaza/packs/" + pack + "/favorite", null).statusCode()).isEqualTo(204);
        approveSecond(pack);
        JsonNode favorite = favorite(learner, pack);
        assertThat(favorite.path("revision").asInt()).isEqualTo(2);
        assertThat(favorite.path("seenRevision").asInt()).isEqualTo(1);
        assertThat(favorite.path("releaseNotes").asString()).contains("判断题第 1 题改了说法");
        assertThat(send(learner, "PUT", "/api/plaza/packs/" + pack + "/favorite/seen", null).statusCode()).isEqualTo(204);
        assertThat(favorite(learner, pack).path("seenRevision").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 辅助

    /** 公开、已审核的第 1 版。 */
    private String pack() {
        String id = Ids.uuidV7();
        Instant now = Instant.now();
        catalog.insertPack(id, author, now);
        catalog.insertRevision(new CatalogMapper.RevisionRow(id, 1, "基础题型", "简介", "说明", "其他", "[]", "", "upload",
                "v1.qbank", HexFormat.of().parseHex("00".repeat(32)), 1, "[]", "approved", null, null, null, null, author, now));
        catalog.insertBanks(id, 1, banks(v1));
        jdbc.update("UPDATE catalog_pack SET approved_revision = 1, visibility = 'public', status = 'active' WHERE id = ?", id);
        jdbc.update("UPDATE catalog_pack_revision SET validation_status = 'validated', review_status = 'approved' WHERE pack_id = ?", id);
        return id;
    }

    /** 提交第 2 版（待审核）。 */
    private void submitSecond(String pack) {
        catalog.insertRevision(new CatalogMapper.RevisionRow(pack, 2, "基础题型", "简介", "说明", "其他", "[]",
                "判断题第 1 题改了说法；去掉单选题", "upload", "v2.qbank", HexFormat.of().parseHex("11".repeat(32)), 1, "[]",
                "pending", null, null, null, null, author, Instant.now()));
        catalog.insertBanks(pack, 2, banks(v2));
        jdbc.update("UPDATE catalog_pack SET latest_revision = 2 WHERE id = ?", pack);
        jdbc.update("UPDATE catalog_pack_revision SET validation_status = 'validated' WHERE pack_id = ? AND revision = 2", pack);
    }

    /** 第 2 版经管理员审核通过（与生产相同的审核路径，含更新通知）。 */
    private void approveSecond(String pack) {
        submitSecond(pack);
        reviews.approve(admin, pack, 2);
    }

    /** 第 3 版（恢复单选题）经管理员审核通过。 */
    private void approveThird(String pack) {
        catalog.insertRevision(new CatalogMapper.RevisionRow(pack, 3, "基础题型", "简介", "说明", "其他", "[]",
                "恢复单选题", "upload", "v3.qbank", HexFormat.of().parseHex("22".repeat(32)), 1, "[]",
                "pending", null, null, null, null, author, Instant.now()));
        catalog.insertBanks(pack, 3, banks(v3));
        jdbc.update("UPDATE catalog_pack SET latest_revision = 3 WHERE id = ?", pack);
        jdbc.update("UPDATE catalog_pack_revision SET validation_status = 'validated' WHERE pack_id = ? AND revision = 3", pack);
        reviews.approve(admin, pack, 3);
    }

    private static List<CatalogMapper.PackBankRow> banks(Map<String, String> version) {
        List<CatalogMapper.PackBankRow> rows = new ArrayList<>();
        int position = 0;
        for (var bank : version.entrySet()) rows.add(new CatalogMapper.PackBankRow(bank.getKey(), bank.getValue(), "题库", position++, 0, null));
        return rows;
    }

    private String importBank(User user, String pack, String uuid) throws Exception {
        JsonNode result = json(send(user, "POST", "/api/plaza/packs/" + pack + "/imports", JSON.createObjectNode()
                .set("banks", JSON.createArrayNode().add(uuid))));
        return result.path("collections").get(0).path("id").asString();
    }

    private static String title(JsonNode overview, String qid) {
        for (JsonNode question : overview.path("questions")) {
            if (question.path("id").asString().equals(qid)) return question.path("title").asString();
        }
        throw new AssertionError("question " + qid + " not in overview");
    }

    private String uuid(String collection) {
        return jdbc.queryForObject("SELECT source_bank_uuid FROM practice_collection WHERE id = ?", String.class, collection);
    }

    private String bankOf(String collection) {
        return jdbc.queryForObject("SELECT bank_id FROM practice_collection WHERE id = ?", String.class, collection);
    }

    private long progressVersion(String collection) {
        return jdbc.queryForObject("SELECT progress_version FROM practice_context WHERE collection_id = ? AND kind = 'practice'",
                Long.class, collection);
    }

    private long revisionOf(String collection, String qid) {
        Long value = jdbc.query("SELECT s.revision FROM practice_question_state s JOIN practice_context x ON x.id = s.context_id "
                + "WHERE x.collection_id = ? AND s.question_id = ?", rows -> rows.next() ? rows.getLong(1) : 0L, collection, qid);
        return value == null ? 0 : value;
    }

    private String listUpdate(User user, String collection) throws Exception {
        for (JsonNode bank : json(get(user, "/api/library/index")).path("banks")) {
            if (bank.path("id").asString().equals(collection)) return bank.path("update").path("status").asString();
        }
        throw new AssertionError("collection not listed");
    }

    private JsonNode favorite(User user, String pack) throws Exception {
        for (JsonNode item : json(get(user, "/api/library/favorites")).path("items")) {
            if (item.path("id").asString().equals(pack)) return item;
        }
        throw new AssertionError("favorite not listed");
    }

    private JsonNode summary(User user, String collection) throws Exception {
        return json(get(user, "/api/collections/bank/" + collection + "/summary"));
    }

    private static ObjectNode lifecycleBody(JsonNode summary) {
        ObjectNode body = JSON.createObjectNode().put("requestId", Ids.uuidV7());
        body.set("roundId", summary.get("roundId"));
        body.set("summaryVersion", summary.get("summaryVersion"));
        return body;
    }

    private HttpResponse<String> action(User user, String collection, String qid, String action, long revision, Object answer,
                                        String contentVersion) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("requestId", Ids.uuidV7()).put("revision", revision).put("action", action);
        body.set("data", JSON.createObjectNode().set("answer", JSON.valueToTree(answer)));
        if (contentVersion != null) body.put("contentVersion", contentVersion);
        return post(user, "/api/collections/bank/" + collection + "/questions/" + qid + "/actions", body);
    }

    /** 202 的后台任务显式推进到完成（练习任务在测试中不自动运行）。 */
    private JsonNode ok(HttpResponse<String> response) {
        if (response.statusCode() == 202) {
            String id = JSON.readTree(response.body()).path("jobId").asString();
            for (int n = 0; n < 200; n++) {
                jobs.tick();
                var row = jdbc.queryForMap("SELECT status, result FROM practice_lifecycle_job WHERE id = ?", id);
                assertThat(row.get("status")).as(row.toString()).isNotEqualTo("failed");
                if ("completed".equals(row.get("status"))) return JSON.readTree((String) row.get("result"));
            }
            throw new AssertionError("job did not finish");
        }
        return json(response);
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 204);
        return response.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    private HttpResponse<String> get(User user, String path) throws Exception {
        return send(user, "GET", path, null);
    }

    private HttpResponse<String> post(User user, String path, JsonNode body) throws Exception {
        return send(user, "POST", path, body);
    }

    private HttpResponse<String> send(User user, String method, String path, JsonNode body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(90))
                .header("Cookie", user.cookie()).header("Origin", ORIGIN).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private User user() {
        var registered = auth.register(Ids.uuidV7() + "@example.test", "学习者", "password-123", null);
        learners.add(registered.userId());
        return new User(registered.userId(), "__Host-qf_session=" + registered.token());
    }

    /** 第 2 版：判断题第 1 题改标题（UUID 不变、内容不同），去掉单选题。 */
    /** 第 3 版：保留第 2 版的判断题改动，并恢复单选题（第 1 题标题加“（第三版）”）。 */
    private static byte[] thirdVersion(byte[] original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(original)); var out = new ZipOutputStream(bytes)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                byte[] content = in.readAllBytes();
                if (entry.getName().endsWith("/判断题/bank.json") || entry.getName().endsWith("/单选题/bank.json")) {
                    boolean judgeBank = entry.getName().endsWith("/判断题/bank.json");
                    ObjectNode bank = (ObjectNode) JSON.readTree(content);
                    ObjectNode question = (ObjectNode) bank.path("questions").get(0);
                    if (!judgeBank) restored = question.path("id").asString();
                    question.put("title", question.path("title").asString() + (judgeBank ? "（第二版）" : "（第三版）"));
                    content = JSON.writeValueAsBytes(bank);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(content);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] secondVersion(byte[] original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(original)); var out = new ZipOutputStream(bytes)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                byte[] content = in.readAllBytes();
                if (entry.getName().startsWith("question-banks/") && entry.getName().contains("/单选题/")) continue;
                if (entry.getName().endsWith("/判断题/bank.json")) {
                    ObjectNode bank = (ObjectNode) JSON.readTree(content);
                    ObjectNode question = (ObjectNode) bank.path("questions").get(0);
                    changed = question.path("id").asString();
                    question.put("title", question.path("title").asString() + "（第二版）");
                    content = JSON.writeValueAsBytes(bank);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(content);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Path temporary() {
        try { return Files.createTempDirectory("qf-plaza-updates-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static Path directory(String name) {
        try { return Files.createDirectory(ROOT.resolve(name)); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
