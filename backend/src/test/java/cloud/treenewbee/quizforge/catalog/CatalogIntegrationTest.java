package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import cloud.treenewbee.quizforge.content.application.BankAvailability;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 题库广场 1/4：从本地上传发布、原文件逐字节保存、整包新版本、删除、审核、拓展白名单与通知。
 * 题库内容由真实的 rule-runner 校验（与私人导入相同的导入代码）。
 */
@Timeout(300)
class CatalogIntegrationTest extends MySqlTestBase {

    static final String TOKEN = "catalog-integration-token";
    static final String ORIGIN = "https://catalog.example.test";
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
        registry.add("qf.import.requests-per-minute", () -> "1000");
        registry.add("qf.import.max-concurrency", () -> "4");
    }

    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankAvailability availability;
    @Autowired cloud.treenewbee.quizforge.content.application.ImportQueueJobs queue;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cloud.treenewbee.quizforge.rules.RuleRunnerClient runner;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    record User(String id, String cookie) {
    }

    @BeforeEach
    void clean() {
        // 此类显式推进队列，只验证持久化与审核；前台让行由独立门控测试覆盖。
        org.mockito.Mockito.doReturn(false).when(runner).foregroundRecentlyActive();
        jdbc.update("UPDATE content_import_slot SET job_id=NULL,lease_until=NULL");
        for (String table : List.of("content_import_job", "content_validation_cache", "catalog_pack_event", "catalog_pack_file", "catalog_pack_bank", "catalog_pack_revision",
                "catalog_pack", "practice_collection", "content_question", "content_bank_asset", "content_bank",
                "content_extension_file", "content_extension_package", "admin_extension_review")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // ------------------------------------------------------------------ 发布：从本地上传

    @Test
    void uploadKeepsTheOriginalFileByteForByteAndRegistersPendingExtensions() throws Exception {
        User owner = user();
        byte[] archive = archive(List.of("单选题", "判断题"), null);
        HttpResponse<String> response = publish(owner, archive, info("刷题合集"), List.of(png(), webp()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String pack = json(response).path("packId").asString();
        assertThat(json(response).path("revision").asInt()).isEqualTo(1);

        String sha = jdbc.queryForObject("SELECT HEX(blob_sha256) FROM catalog_pack_revision WHERE pack_id = ?", String.class, pack);
        assertThat(sha).isEqualToIgnoringCase(sha256(archive));
        Path stored = BLOBS.resolve("sha256").resolve(sha.toLowerCase().substring(0, 2)).resolve(sha.toLowerCase().substring(2, 4))
                .resolve(sha.toLowerCase());
        assertThat(Files.readAllBytes(stored)).isEqualTo(archive);
        assertThat(jdbc.queryForObject("SELECT size FROM catalog_pack_revision WHERE pack_id = ?", Long.class, pack))
                .isEqualTo(archive.length);

        // 导入时为未知的拓展摘要自动创建待审核记录；版本记下所用的拓展摘要。
        assertThat(jdbc.queryForList("SELECT status FROM admin_extension_review", String.class)).hasSize(2).containsOnly("pending");
        assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(extension_digests) FROM catalog_pack_revision WHERE pack_id = ?",
                Integer.class, pack)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT bank_uuid FROM catalog_pack_bank WHERE pack_id = ? ORDER BY position", String.class, pack))
                .containsExactlyInAnyOrder(ImportArchives.BANK_IDS.get("单选题"), ImportArchives.BANK_IDS.get("判断题"));
        // 公开题库不计入个人存储配额，也不创建题库实例。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id = ?", Long.class, owner.id())).isZero();

        JsonNode mine = json(get(owner, "/api/catalog/my-packs")).path("items");
        assertThat(mine).hasSize(1);
        JsonNode revision = mine.get(0).path("revisions").get(0);
        assertThat(revision.path("reviewStatus").asString()).isEqualTo("pending");
        assertThat(revision.path("screenshots")).hasSize(2);
        assertThat(revision.path("banks")).hasSize(2);
        assertThat(get(owner, "/api/catalog/files/" + revision.path("screenshots").get(0).path("id").asString()).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT action FROM catalog_pack_event WHERE pack_id = ?", String.class, pack))
                .isEqualTo("published");
    }

    @Test
    void publishingValidatesInfoScreenshotsRightsAndFileType() throws Exception {
        User owner = user();
        byte[] archive = archive(List.of("单选题"), null);
        Map<String, String> missingTitle = info(" ");
        assertThat(publish(owner, archive, missingTitle, List.of()).statusCode()).isEqualTo(400);
        Map<String, String> noRights = info("合集");
        noRights.remove("rights");
        assertThat(publish(owner, archive, noRights, List.of()).statusCode()).isEqualTo(400);
        Map<String, String> tooManyTags = info("合集");
        tooManyTags.put("tags", "一,二,三,四,五,六");
        assertThat(publish(owner, archive, tooManyTags, List.of()).statusCode()).isEqualTo(400);
        Map<String, String> badCategory = info("合集");
        badCategory.put("category", "不存在");
        assertThat(publish(owner, archive, badCategory, List.of()).statusCode()).isEqualTo(400);
        assertThat(publish(owner, archive, info("合集"), List.of("<svg/>".getBytes(StandardCharsets.UTF_8))).statusCode()).isEqualTo(400);
        List<byte[]> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) seven.add(png());
        assertThat(publish(owner, archive, info("合集"), seven).statusCode()).isEqualTo(400);
        byte[] big = new byte[3 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, big, 0, png().length);
        assertThat(publish(owner, archive, info("合集"), List.of(big)).statusCode()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_pack", Long.class)).isZero();
    }

    // ------------------------------------------------------------------ 私人导入记录题库 UUID

    @Test
    void privateImportsRecordTheBankUuidAndFillItOnSharedContent() throws Exception {
        User owner = user();
        HttpResponse<String> imported = privateUpload(owner, archive(List.of("单选题", "判断题"), null));
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(202);
        String privatePack = json(imported).path("packId").asString();
        jdbc.update("UPDATE admin_extension_review SET status='approved'");
        var bankIds = jdbc.queryForList("SELECT bank_uuid FROM catalog_pack_bank WHERE pack_id=?", String.class, privatePack);
        assertThat(post(owner, "/api/library/packs/" + privatePack + "/practice", JSON.writeValueAsString(Map.of("banks", bankIds))).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT source_bank_uuid FROM practice_collection WHERE user_id = ?", String.class, owner.id()))
                .containsExactlyInAnyOrder(ImportArchives.BANK_IDS.get("单选题"), ImportArchives.BANK_IDS.get("判断题"));
        assertThat(jdbc.queryForList("SELECT bank_uuid FROM content_bank", String.class))
                .containsExactlyInAnyOrder(ImportArchives.BANK_IDS.get("单选题"), ImportArchives.BANK_IDS.get("判断题"));
        // V21 之前导入的内容没有 UUID：再次导入相同内容（发布时复用共享内容）时补上。
        jdbc.update("UPDATE content_bank SET bank_uuid = NULL");
        HttpResponse<String> published = publish(owner, archive(List.of("单选题", "判断题"), null), info("合集"), List.of());
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_bank", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_bank WHERE bank_uuid IS NULL", Long.class)).isZero();
        // 从“我的题库”（练习实例）选择发布的入口已去掉（设计 04 第 6.0 节：从私人题库包发布在下一个 PR 中加入）。
        assertThat(post(owner, "/api/catalog/packs/from-library", null).statusCode()).isIn(404, 405);
    }

    // ------------------------------------------------------------------ 审核

    @Test
    void approvalRequiresApprovedExtensionsThenPublishesAndNotifiesThePublisher() throws Exception {
        User owner = user();
        User admin = admin();
        String pack = json(publish(owner, archive(List.of("单选题", "判断题"), null), info("合集"), List.of(png()))).path("packId").asString();

        JsonNode pending = json(get(admin, "/api/admin/catalog/reviews")).path("items");
        assertThat(pending).hasSize(1);
        JsonNode detail = json(get(admin, "/api/admin/catalog/packs/" + pack + "/revisions/1"));
        assertThat(detail.path("allExtensionsApproved").asBoolean()).isFalse();
        assertThat(detail.path("diff")).extracting(node -> node.path("change").asString()).containsOnly("new");
        assertThat(detail.path("diff")).extracting(node -> node.path("questionCount").asInt()).allMatch(count -> count > 0);
        assertThat(detail.path("revision").path("screenshots")).hasSize(1);
        // 管理员可以读取待审核版本的截图，其他用户不能。
        String shot = detail.path("revision").path("screenshots").get(0).path("id").asString();
        assertThat(get(admin, "/api/catalog/files/" + shot).statusCode()).isEqualTo(200);
        assertThat(get(user(), "/api/catalog/files/" + shot).statusCode()).isEqualTo(404);
        assertThat(get(admin, "/api/admin/catalog/packs/" + pack + "/revisions/1/archive").headers()
                .firstValue("X-Accel-Redirect")).isPresent();

        HttpResponse<String> blocked = post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/1/approve", null);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(blocked.body()).contains("EXTENSIONS_NOT_APPROVED");

        for (JsonNode extension : detail.path("extensions")) {
            assertThat(post(admin, "/api/admin/extensions/" + extension.path("digest").asString() + "/approve", null).statusCode())
                    .isEqualTo(200);
        }
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/1/approve", null).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT approved_revision FROM catalog_pack WHERE id = ?", Integer.class, pack)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT review_status FROM catalog_pack_revision WHERE pack_id = ?", String.class, pack))
                .isEqualTo("approved");
        // 每个版本只审核一次。
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/1/reject", "{\"reason\":\"改主意\"}").statusCode())
                .isEqualTo(409);
        // 审核结果通过站内通知发给发布者。
        JsonNode notes = json(get(owner, "/api/notifications")).path("items");
        assertThat(notes.get(0).path("title").asString()).contains("合集", "第 1 版审核通过");
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit WHERE actor_id = ? ORDER BY created_at, id", String.class,
                admin.id())).containsExactly("extension-approved", "extension-approved", "pack-revision-approved");
        // 通过后公开：截图对所有登录用户可见。
        assertThat(get(user(), "/api/catalog/files/" + shot).statusCode()).isEqualTo(200);
        // 实时版本状态必须参与 SQL 授权，不能仅凭 approved_revision 指针继续公开截图。
        jdbc.update("UPDATE catalog_pack_revision SET review_status='pending' WHERE pack_id=? AND revision=1", pack);
        assertThat(get(user(), "/api/catalog/files/" + shot).statusCode()).isEqualTo(404);
        assertThat(get(owner, "/api/catalog/files/" + shot).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE catalog_pack_revision SET review_status='approved' WHERE pack_id=? AND revision=1", pack);
        jdbc.update("UPDATE catalog_pack SET visibility='private' WHERE id=?", pack);
        assertThat(get(user(), "/api/catalog/files/" + shot).statusCode()).isEqualTo(404);
    }

    @Test
    void newRevisionsKeepThePreviousApprovedVersionPublicAndRejectedVersionsCannotBeResubmitted() throws Exception {
        User owner = user();
        User admin = admin();
        String pack = json(publish(owner, archive(List.of("单选题", "判断题"), null), info("合集"), List.of(png()))).path("packId").asString();
        approveEverything(pack, 1);
        String shot = json(get(owner, "/api/catalog/my-packs")).path("items").get(0).path("revisions").get(0)
                .path("screenshots").get(0).path("id").asString();

        // 第 2 版：单选题内容变化（UUID 相同）、去掉判断题、新增多选题。沿用第 1 版的截图。
        byte[] second = archive(List.of("单选题", "多选题"),
                files -> ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> bank.put("description", "第二版")));
        Map<String, String> notes = info("合集 v2");
        notes.put("releaseNotes", "修正第 3 题");
        HttpResponse<String> revised = revise(owner, pack, second, notes, List.of(), List.of(shot));
        assertThat(revised.statusCode()).as(revised.body()).isEqualTo(201);
        assertThat(json(revised).path("revision").asInt()).isEqualTo(2);

        JsonNode detail = json(get(admin, "/api/admin/catalog/packs/" + pack + "/revisions/2"));
        Map<String, String> changes = new LinkedHashMap<>();
        for (JsonNode item : detail.path("diff")) changes.put(item.path("uuid").asString(), item.path("change").asString());
        assertThat(changes).containsEntry(ImportArchives.BANK_IDS.get("单选题"), "updated")
                .containsEntry(ImportArchives.BANK_IDS.get("判断题"), "removed")
                .containsEntry(ImportArchives.BANK_IDS.get("多选题"), "added").hasSize(3);
        assertThat(detail.path("identicalToApproved").asBoolean()).isFalse();
        assertThat(detail.path("revision").path("screenshots")).hasSize(1);
        assertThat(detail.path("revision").path("releaseNotes").asString()).isEqualTo("修正第 3 题");

        // 审核期间继续公开第 1 版：加入时拿到的是第 1 版的内容。
        assertThat(jdbc.queryForObject("SELECT approved_revision FROM catalog_pack WHERE id = ?", Integer.class, pack)).isEqualTo(1);
        User learner = user();
        HttpResponse<String> joinOld = post(learner, "/api/plaza/packs/" + pack + "/imports",
                "{\"banks\":[\"" + ImportArchives.BANK_IDS.get("判断题") + "\"]}");
        assertThat(joinOld.statusCode()).as(joinOld.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT source_revision FROM practice_collection WHERE user_id = ?", Integer.class, learner.id()))
                .isEqualTo(1);
        // 第 2 版的多选题还没公开，不能加入。
        assertThat(post(learner, "/api/plaza/packs/" + pack + "/imports",
                "{\"banks\":[\"" + ImportArchives.BANK_IDS.get("多选题") + "\"]}").statusCode()).isEqualTo(400);

        // 拒绝必须填写原因；拒绝后发布者收到原因，同一版本不能再审核。
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/2/reject", "{\"reason\":\"  \"}").statusCode())
                .isEqualTo(400);
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/2/reject", "{\"reason\":\"多选题答案错误\"}").statusCode())
                .isEqualTo(204);
        JsonNode rejected = json(get(owner, "/api/notifications")).path("items").get(0);
        assertThat(rejected.path("title").asString()).contains("第 2 版未通过审核");
        JsonNode note = json(get(owner, "/api/notifications/" + rejected.path("id").asString()));
        assertThat(note.toString()).contains("多选题答案错误");
        for (JsonNode extension : detail.path("extensions")) {
            post(admin, "/api/admin/extensions/" + extension.path("digest").asString() + "/approve", null);
        }
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/2/approve", null).statusCode()).isEqualTo(409);
        JsonNode mine = json(get(owner, "/api/catalog/my-packs")).path("items").get(0).path("revisions");
        assertThat(mine.get(0).path("reviewStatus").asString()).isEqualTo("rejected");
        assertThat(mine.get(0).path("reviewNote").asString()).isEqualTo("多选题答案错误");
        assertThat(jdbc.queryForObject("SELECT approved_revision FROM catalog_pack WHERE id = ?", Integer.class, pack)).isEqualTo(1);

        // 发布第 3 版（与第 1 版完全相同）：审核页面提示管理员；再提交第 4 版时，第 3 版不再审核。
        HttpResponse<String> third = revise(owner, pack, archive(List.of("单选题", "判断题"), null), info("合集"), List.of(), List.of());
        assertThat(third.statusCode()).as(third.body()).isEqualTo(201);
        assertThat(json(get(admin, "/api/admin/catalog/packs/" + pack + "/revisions/3")).path("identicalToApproved").asBoolean()).isTrue();
        assertThat(revise(owner, pack, second, info("合集"), List.of(), List.of()).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT review_status FROM catalog_pack_revision WHERE pack_id = ? AND revision = 3",
                String.class, pack)).isEqualTo("superseded");
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/3/approve", null).statusCode()).isEqualTo(409);
        assertThat(json(get(admin, "/api/admin/catalog/reviews")).path("items")).extracting(item -> item.path("revision").asInt())
                .containsExactly(4);
        assertThat(jdbc.queryForList("SELECT action FROM catalog_pack_event WHERE pack_id = ? ORDER BY created_at, id",
                String.class, pack)).containsExactly("published", "approved", "revised", "rejected", "revised", "revised");
    }

    // ------------------------------------------------------------------ 归属与删除

    @Test
    void onlyTheOwnerCanReviseOrDeleteAndDeletedPacksCannotBeJoined() throws Exception {
        User owner = user();
        User stranger = user();
        String pack = json(publish(owner, archive(List.of("单选题"), null), info("合集"), List.of())).path("packId").asString();
        approveEverything(pack, 1);
        User learner = user();
        String body = "{\"banks\":[\"" + ImportArchives.BANK_IDS.get("单选题") + "\"]}";
        String collection = json(post(learner, "/api/plaza/packs/" + pack + "/imports", body)).path("collections").get(0).path("id").asString();
        // 每次导入都创建独立实例，互不覆盖作答。
        assertThat(json(post(learner, "/api/plaza/packs/" + pack + "/imports", body)).path("collections").get(0).path("id").asString())
                .isNotEqualTo(collection);

        assertThat(revise(stranger, pack, archive(List.of("单选题"), null), info("劫持"), List.of(), List.of()).statusCode())
                .isEqualTo(404);
        assertThat(send(stranger, "DELETE", "/api/catalog/packs/" + pack, null).statusCode()).isEqualTo(404);
        assertThat(json(get(stranger, "/api/catalog/my-packs")).path("items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT latest_revision FROM catalog_pack WHERE id = ?", Integer.class, pack)).isEqualTo(1);

        // 第 2 版待审核时删除整个包：从广场下架、待审核版本撤回；已加入的用户保留题库。
        assertThat(revise(owner, pack, archive(List.of("单选题", "判断题"), null), info("合集"), List.of(), List.of()).statusCode())
                .isEqualTo(201);
        assertThat(send(owner, "DELETE", "/api/catalog/packs/" + pack, null).statusCode()).isEqualTo(204);
        assertThat(send(owner, "DELETE", "/api/catalog/packs/" + pack, null).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM catalog_pack WHERE id = ?", String.class, pack)).isEqualTo("deleted");
        assertThat(jdbc.queryForObject("SELECT review_status FROM catalog_pack_revision WHERE pack_id = ? AND revision = 2",
                String.class, pack)).isEqualTo("withdrawn");
        assertThat(post(user(), "/api/plaza/packs/" + pack + "/imports", body).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM practice_collection WHERE id = ?", Boolean.class, collection)).isTrue();
        assertThat(json(get(admin(), "/api/admin/catalog/reviews")).path("items")).isEmpty();
        assertThat(revise(owner, pack, archive(List.of("单选题"), null), info("合集"), List.of(), List.of()).statusCode()).isEqualTo(404);
        assertThat(json(get(owner, "/api/catalog/my-packs")).path("items").get(0).path("status").asString()).isEqualTo("deleted");
    }

    @Test
    void reviewAndExtensionEndpointsRequireAdministrators() throws Exception {
        User owner = user();
        String pack = json(publish(owner, archive(List.of("单选题"), null), info("合集"), List.of())).path("packId").asString();
        String digest = jdbc.queryForObject("SELECT LOWER(HEX(digest)) FROM admin_extension_review LIMIT 1", String.class);
        for (String[] endpoint : List.of(
                new String[] {"GET", "/api/admin/catalog/reviews", null},
                new String[] {"GET", "/api/admin/catalog/packs/" + pack + "/revisions/1", null},
                new String[] {"GET", "/api/admin/catalog/packs/" + pack + "/revisions/1/archive", null},
                new String[] {"POST", "/api/admin/catalog/packs/" + pack + "/revisions/1/approve", null},
                new String[] {"POST", "/api/admin/catalog/packs/" + pack + "/revisions/1/reject", "{\"reason\":\"x\"}"},
                new String[] {"GET", "/api/admin/extensions", null},
                new String[] {"GET", "/api/admin/extensions/" + digest + "/impact", null},
                new String[] {"POST", "/api/admin/extensions/" + digest + "/approve", null},
                new String[] {"POST", "/api/admin/extensions/" + digest + "/revoke", "{\"reason\":\"x\"}"})) {
            assertThat(send(owner, endpoint[0], endpoint[1], endpoint[2]).statusCode()).as(endpoint[1]).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM admin_extension_review LIMIT 1", String.class)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT review_status FROM catalog_pack_revision", String.class)).isEqualTo("pending");
    }

    // ------------------------------------------------------------------ 拓展白名单

    @Test
    void privateBankAvailabilityFollowsTheWhitelistAndRevocationShowsItsImpact() throws Exception {
        User owner = user();
        User admin = admin();
        HttpResponse<String> imported = privateUpload(owner, archive(List.of("单选题"), null));
        String privatePack = json(imported).path("packId").asString();
        String bank = jdbc.queryForObject("SELECT bank_id FROM catalog_pack_bank WHERE pack_id=?", String.class, privatePack);
        String digest = jdbc.queryForObject("SELECT LOWER(HEX(digest)) FROM admin_extension_review", String.class);
        assertThat(availability.state(bank).message()).isEqualTo("审核中");
        assertThat(json(get(admin, "/api/admin/extensions?status=pending")).path("items")).hasSize(1);

        assertThat(post(admin, "/api/admin/extensions/" + digest + "/revoke", "{\"reason\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(post(admin, "/api/admin/extensions/" + digest + "/revoke", "{\"reason\":\"死循环\"}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM admin_extension_review", String.class)).isEqualTo("rejected");
        assertThat(availability.state(bank).message()).isEqualTo("未通过：死循环");

        assertThat(post(admin, "/api/admin/extensions/" + digest + "/approve", null).statusCode()).isEqualTo(200);
        assertThat(availability.state(bank).available()).isTrue();
        var bankIds = jdbc.queryForList("SELECT bank_uuid FROM catalog_pack_bank WHERE pack_id=?", String.class, privatePack);
        assertThat(post(owner, "/api/library/packs/" + privatePack + "/practice", JSON.writeValueAsString(Map.of("banks", bankIds))).statusCode()).isEqualTo(200);

        // 新流程同时保留私人题库包；影响列表覆盖该资源包、练习实例和广场包。
        String pack = json(publish(owner, archive(List.of("单选题"), null), info("合集"), List.of())).path("packId").asString();
        approveEverything(pack, 1);
        JsonNode impact = json(get(admin, "/api/admin/extensions/" + digest + "/impact")).path("impact");
        assertThat(impact.path("privateCollections").asLong()).isEqualTo(1);
        assertThat(impact.path("packs")).hasSize(2);
        assertThat(impact.path("packs")).anySatisfy(item -> {
            assertThat(item.path("packId").asString()).isEqualTo(pack);
            assertThat(item.path("current").asBoolean()).isTrue();
        }).anySatisfy(item -> {
            assertThat(item.path("packId").asString()).isEqualTo(privatePack);
            assertThat(item.path("current").asBoolean()).isFalse();
        });

        assertThat(post(admin, "/api/admin/extensions/" + digest + "/revoke", "{\"reason\":\"发现内存炸弹\"}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM admin_extension_review", String.class)).isEqualTo("revoked");
        assertThat(availability.state(bank).message()).isEqualTo("拓展已停用");
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit WHERE actor_id = ? AND module = 'admin' ORDER BY created_at, id",
                String.class, admin.id())).containsExactly("extension-rejected", "extension-approved", "extension-revoked");
        // 再次导入相同的拓展：已有记录（已撤销）不被重置为待审核。
        privateUpload(owner, archive(List.of("单选题"), null));
        assertThat(jdbc.queryForObject("SELECT status FROM admin_extension_review", String.class)).isEqualTo("revoked");
    }

    @Test
    void newVersionsRequireReleaseNotesBecauseTheyAreSentToLearners() throws Exception {
        User owner = user();
        String pack = json(publish(owner, archive(List.of("单选题"), null), info("合集"), List.of())).path("packId").asString();
        // 不经过 revise 辅助方法（它会补一段更新说明）：直接提交缺少更新说明的新版本。
        HttpResponse<String> missing = multipart(owner, "/api/catalog/packs/" + pack + "/revisions", info("合集"),
                archive(List.of("单选题"), null), List.of(), List.of());
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(400);
        assertThat(missing.body()).contains("更新说明");
        assertThat(jdbc.queryForObject("SELECT latest_revision FROM catalog_pack WHERE id = ?", Integer.class, pack)).isEqualTo(1);
        Map<String, String> blank = info("合集");
        blank.put("releaseNotes", "   ");
        assertThat(multipart(owner, "/api/catalog/packs/" + pack + "/revisions", blank, archive(List.of("单选题"), null),
                List.of(), List.of()).statusCode()).isEqualTo(400);
    }

    @Test
    void reviewDetailListsExternalAudioHosts() throws Exception {
        User owner = user();
        User admin = admin();
        byte[] archive = archive(List.of("单选题"), files -> ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json",
                bank -> bank.put("listening", Map.of("materials", List.of(
                        Map.of("id", "a", "title", "A", "url", "https://media.example.com/listening/a.mp3"),
                        Map.of("id", "b", "title", "B", "url", "https://cdn.example.org/b.mp3"))))));
        HttpResponse<String> published = publish(owner, archive, info("听力"), List.of());
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        JsonNode detail = json(get(admin, "/api/admin/catalog/packs/" + json(published).path("packId").asString() + "/revisions/1"));
        assertThat(detail.path("audioHosts")).extracting(JsonNode::asString).containsExactly("cdn.example.org", "media.example.com");
    }

    // ------------------------------------------------------------------ 辅助

    private void approveEverything(String pack, int revision) throws Exception {
        jdbc.update("UPDATE admin_extension_review SET status = 'approved'");
        User admin = admin();
        assertThat(post(admin, "/api/admin/catalog/packs/" + pack + "/revisions/" + revision + "/approve", null).statusCode())
                .isEqualTo(204);
    }

    private static Map<String, String> info(String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("summary", "一句话简介");
        fields.put("description", "详细说明\n第二段");
        fields.put("category", "考试认证");
        fields.put("tags", "英语, 四级");
        fields.put("rights", "yes");
        return fields;
    }

    private byte[] archive(List<String> types, Consumer<Map<String, byte[]>> change) throws Exception {
        Map<String, byte[]> files = ImportArchives.files(types, "group");
        if (change != null) change.accept(files);
        return ImportArchives.zip(files);
    }

    private HttpResponse<String> publish(User user, byte[] archive, Map<String, String> fields, List<byte[]> shots) throws Exception {
        return validated(multipart(user, "/api/catalog/packs", fields, archive, shots, List.of()));
    }

    private HttpResponse<String> revise(User user, String pack, byte[] archive, Map<String, String> fields, List<byte[]> shots,
                                        List<String> keep) throws Exception {
        // 新版本必须填写更新说明（随题库更新通知发送）；用例没有指定时补一段。
        Map<String, String> withNotes = new LinkedHashMap<>(fields);
        withNotes.putIfAbsent("releaseNotes", "更新说明");
        return validated(multipart(user, "/api/catalog/packs/" + pack + "/revisions", withNotes, archive, shots, keep));
    }

    // 既有审核用例显式推进后台任务；接收请求本身的异步行为由队列用例断言。
    private HttpResponse<String> validated(HttpResponse<String> response) {
        if (response.statusCode() == 201) assertThat(queue.runOne()).isTrue();
        return response;
    }

    private HttpResponse<String> privateUpload(User user, byte[] archive) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri("/api/library/uploads?filename=test.qbank")).timeout(Duration.ofSeconds(120))
                .header("Origin", ORIGIN).header("Cookie", user.cookie()).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(archive)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(queue.runOne()).isTrue();
        return response;
    }

    private HttpResponse<String> multipart(User user, String path, Map<String, String> fields, byte[] archive, List<byte[]> shots,
                                           List<String> keep) throws Exception {
        String boundary = "qf-" + Ids.uuidV7();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) field(body, boundary, field.getKey(), field.getValue());
        for (String id : keep) field(body, boundary, "keepScreenshots", id);
        if (archive != null) file(body, boundary, "archive", "测试题库.qbank", archive);
        int index = 0;
        for (byte[] shot : shots) file(body, boundary, "screenshots", "shot-" + index++ + ".png", shot);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(120)).header("Origin", ORIGIN)
                .header("Cookie", user.cookie()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void file(ByteArrayOutputStream body, String boundary, String name, String filename, byte[] bytes)
            throws Exception {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8) + "; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(User user, String path) throws Exception {
        return send(user, "GET", path, null);
    }

    private HttpResponse<String> post(User user, String path, String json) throws Exception {
        return send(user, "POST", path, json);
    }

    private HttpResponse<String> send(User user, String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(120))
                .header("Origin", ORIGIN).header("Cookie", user.cookie())
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 202);
        return JSON.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private User user() {
        var registered = auth.register(Ids.uuidV7() + "@example.test", "发布者", "pass12345", null);
        return new User(registered.userId(), "__Host-qf_session=" + registered.token());
    }

    private User admin() {
        User user = user();
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", user.id());
        return user;
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
    }

    private static byte[] webp() {
        return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0, 0};
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path temporary() {
        try { return Files.createTempDirectory("qf-catalog-test-").toRealPath(); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    private static Path directory(String name) {
        try { return Files.createDirectories(ROOT.resolve(name)); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
}
