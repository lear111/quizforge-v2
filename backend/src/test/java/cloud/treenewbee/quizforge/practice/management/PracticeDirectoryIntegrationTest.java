package cloud.treenewbee.quizforge.practice.management;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.quota.application.PrivateCollectionService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.json.JsonMapper;

@Timeout(180)
class PracticeDirectoryIntegrationTest extends MySqlTestBase {
    static final Path ROOT = temporary();
    static final String TOKEN = "library-test-token";
    static final GenericContainer<?> RUNNER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath(".", Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner").normalize()))
            .withExposedPorts(8090).withEnv("QF_RULE_RUNNER_TOKEN", TOKEN).withEnv("QF_BLOB_DIR", "/blobs")
            .withFileSystemBind(ROOT.toString(), "/blobs", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/v1/health").forPort(8090).withHeader("X-Internal-Token", TOKEN));
    static { RUNNER.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.storage.blob-dir", ROOT::toString);
        registry.add("qf.rule-runner.base-url", () -> "http://" + RUNNER.getHost() + ":" + RUNNER.getMappedPort(8090));
        registry.add("qf.rule-runner.token", () -> TOKEN);
        registry.add("qf.rule-runner.default-deadline", () -> "60s");
    }

    @Autowired PracticeDirectoryService library;
    @Autowired PracticeDirectoryMapper mapper;
    @Autowired cloud.treenewbee.quizforge.testsupport.QueuedPracticeImports imports;
    @Autowired PracticeReadService reads;
    @Autowired QuotaService quotas;
    @Autowired cloud.treenewbee.quizforge.content.application.ImportUploadService uploads;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @org.springframework.beans.factory.annotation.Value("${qf.security.allowed-origin}") String origin;
    @MockitoSpyBean PrivateCollectionService deletion;
    String user;

    @BeforeEach void prepare() { user = user(); }
    @AfterEach void restore() { reset(deletion); }
    @AfterAll static void stop() { RUNNER.stop(); }

    @Test void v20CreatesOnlyPracticeDirectoryTables() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN
                    ('practice_directory_revision', 'practice_group', 'library_catalog', 'library_group', 'content_bank_document')
                """, String.class)).containsExactlyInAnyOrder("practice_directory_revision", "practice_group");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='20' AND success=TRUE", Integer.class))
                .isEqualTo(1);
    }

    @Test void renameChangesOnlyDisplayNameAndRejectsCaseInsensitiveDuplicate() throws Exception {
        var first = imported(user, "单选题");
        var second = imported(user, "多选题");
        update(user, first.id(), "Name", first.groupName());
        assertThat(mapper.item(user, first.id()).orElseThrow().bankId()).isEqualTo(first.bankId());
        assertThatThrownBy(() -> update(user, second.id(), "nAME", first.groupName()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("NAME_TAKEN"));
        assertThat(mapper.item(user, second.id()).orElseThrow().title()).isEqualTo(second.title());
    }

    @Test void emptyGroupCanBeCreatedRenamedMovedIntoAndDeleted() throws Exception {
        change(user, "group", "create", "", "空文件夹", null);
        assertThat(reads.catalog(user).groups().get("banks")).contains("空文件夹");
        change(user, "group", "update", "空文件夹", "新文件夹", null);
        var bank = imported(user, "单选题");
        update(user, bank.id(), bank.title(), "新文件夹");
        assertThat(mapper.item(user, bank.id()).orElseThrow().groupName()).isEqualTo("新文件夹");
        update(user, bank.id(), bank.title(), "");
        assertThat(mapper.item(user, bank.id()).orElseThrow().groupName()).isNull();
        assertThat(change(user, "group", "delete", "新文件夹", null, null).remaining()).isZero();
        assertThat(mapper.groups(user)).doesNotContain("新文件夹");
    }

    @Test void groupRenameUpdatesAllOwnedMembersAndLeavesOtherUsersAlone() throws Exception {
        imported(user, "单选题");
        imported(user, "多选题");
        String other = user();
        imported(other, "单选题");
        change(user, "group", "update", ImportArchives.GROUP, "重命名", null);
        assertThat(mapper.items(user)).allSatisfy(item -> assertThat(item.groupName()).isEqualTo("重命名"));
        assertThat(mapper.items(other)).allSatisfy(item -> assertThat(item.groupName()).isEqualTo(ImportArchives.GROUP));
    }

    @Test void differentBankWithSameFolderNameGetsImportSuffix() throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "bank");
        imported(user, "单选题");
        ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> bank.put("id", UUID.randomUUID().toString()));
        var result = imports.importArchive(user, "another.qbank", new ByteArrayInputStream(ImportArchives.zip(files)), ImportArchives.GROUP).getFirst();
        assertThat(result.title()).isEqualTo("单选题（2）");
    }

    @Test void sameUuidCanBeImportedAndMovedWithinOneGroupAndAcrossGroups() throws Exception {
        var first = imported(user, "单选题");
        var duplicate = imported(user, "单选题");
        assertThat(duplicate.title()).isEqualTo("单选题（2）");
        assertThat(duplicate.bankId()).isEqualTo(first.bankId());
        update(user, first.id(), "根目录实例", null);
        var second = imported(user, "单选题");
        assertThat(second.bankId()).isEqualTo(first.bankId());
        update(user, first.id(), "另一显示名称", second.groupName());
        assertThat(mapper.item(user, first.id()).orElseThrow().groupName()).isEqualTo(second.groupName());
        library.deleteOne(user, second.id());
        assertThat(imported(user, "单选题").id()).isNotEqualTo(second.id());
    }

    @Test void sameUuidDifferentVersionsRemainIndependentInstancesInOneGroup() throws Exception {
        var first = imported(user, "单选题");
        var files = ImportArchives.files(List.of("单选题"), "bank");
        ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> bank.put("description", "作者的新版本"));
        var second = imports.importArchive(user, "new.qbank", new ByteArrayInputStream(ImportArchives.zip(files)), ImportArchives.GROUP).getFirst();
        assertThat(second.bankId()).isNotEqualTo(first.bankId());
        assertThat(second.title()).isEqualTo("单选题（2）");
        assertThat(mapper.items(user)).hasSize(2);
    }

    @Test void concurrentDuplicateImportsCreateTwoNamedInstancesWithSeparateCharges() throws Exception {
        byte[] archive = ImportArchives.zip(ImportArchives.files(List.of("单选题"), "bank"));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    imports.importArchive(user, "duplicate.qbank", new ByteArrayInputStream(archive));
                    return "ok";
                } catch (ApiException error) { return error.code(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(futures.get(0).get(90, TimeUnit.SECONDS), futures.get(1).get(90, TimeUnit.SECONDS)))
                    .containsExactly("ok", "ok");
        }
        assertThat(mapper.items(user)).extracting(PracticeDirectoryMapper.Item::title).containsExactlyInAnyOrder("单选题", "单选题（2）");
        assertThat(mapper.items(user)).extracting(PracticeDirectoryMapper.Item::bankId).containsOnly(mapper.items(user).getFirst().bankId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=?", Integer.class, user)).isEqualTo(2);
    }

    @Test void concurrentRenameAndMoveUsingSameVersionHaveExactlyOneWinner() throws Exception {
        var bank = imported(user, "单选题");
        long revision = mapper.revision(user);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (String name : List.of("改名一", "改名二")) futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    library.manage(user, new PracticeDirectoryService.Change("bank", "item", "update", bank.id(), name, name, revision));
                    return "ok";
                } catch (ApiException error) { return error.code(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "LIBRARY_CONFLICT");
        }
        assertThat(mapper.revision(user)).isEqualTo(revision + 1);
    }

    @Test void ownershipIsEnforcedForManagement() throws Exception {
        var bank = imported(user, "单选题");
        String other = user();
        assertThatThrownBy(() -> update(other, bank.id(), "越权", null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status().value()).isEqualTo(404));
        assertThat(mapper.item(user, bank.id()).orElseThrow().title()).isEqualTo(bank.title());
    }

    @Test void realHttpUsesSessionIdentityAndValidatesRevision() throws Exception {
        var session = auth.register(UUID.randomUUID() + "@example.test", "HTTP 测试", "password123", "test");
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var uri = java.net.URI.create("http://localhost:" + port);
            var post = java.net.http.HttpRequest.newBuilder(uri.resolve("/api/library/manage"))
                    .header("Origin", origin).header("Content-Type", "application/json")
                    .header("Cookie", "__Host-qf_session=" + session.token());
            var missing = http.send(post.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"kind\":\"bank\",\"target\":\"group\",\"action\":\"create\",\"name\":\"HTTP\"}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(400);
            var created = http.send(post.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"kind\":\"bank\",\"target\":\"group\",\"action\":\"create\",\"name\":\"HTTP\",\"revision\":0}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(200);
            var foreign = imported(user, "单选题");
            var denied = http.send(post.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("kind", "bank", "target", "item", "action", "update",
                            "path", foreign.id(), "name", "越权", "revision", mapper.revision(session.userId()))))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(404);
            assertThat(mapper.item(user, foreign.id()).orElseThrow().title()).isEqualTo(foreign.title());
        }
    }

    @Test void groupDeletionKeepsCommittedBanksAndCanResumeAfterFailure() throws Exception {
        imported(user, "单选题");
        imported(user, "多选题");
        var members = mapper.items(user);
        doThrow(new IllegalStateException("模拟第二本删除失败")).when(deletion).delete(user, members.get(1).id());
        for (String pack : jdbc.queryForList("SELECT id FROM catalog_pack WHERE owner_id=? AND status='active'", String.class, user))
            uploads.delete(user, pack);
        long before = quotas.usage(user).storage().usedBytes();
        var first = change(user, "group", "delete", ImportArchives.GROUP, null, null);
        assertThat(first.partial()).isTrue();
        assertThat(first.deletedCount()).isEqualTo(1);
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(mapper.item(user, members.getFirst().id())).isEmpty();
        assertThat(quotas.usage(user).storage().usedBytes()).isBetween(1L, before - 1);
        reset(deletion);
        var resumed = change(user, "group", "delete", ImportArchives.GROUP, null, null);
        assertThat(resumed.remaining()).isZero();
        assertThat(quotas.usage(user).storage().usedBytes()).isZero();
        assertThat(mapper.groups(user)).doesNotContain(ImportArchives.GROUP);
    }

    @Test void deletionCascadesProgressHistoryNotesAndActiveJobs() throws Exception {
        var bank = imported(user, "单选题");
        String context = Ids.uuidV7(), round = Ids.uuidV7();
        jdbc.update("INSERT INTO practice_context (id,user_id,collection_id,kind,scope_key,created_at,updated_at) VALUES (?,?,?,'practice','practice',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", context, user, bank.id());
        jdbc.update("INSERT INTO practice_question_state (context_id,question_id,user_id,status,revision,content_sha256,updated_at) VALUES (?,'q1',?,'draft',1,UNHEX(REPEAT('11',32)),CURRENT_TIMESTAMP(3))", context, user);
        jdbc.update("INSERT INTO practice_round (id,user_id,collection_id,context_id,mode,status,collection_snapshot,created_at,updated_at) VALUES (?,?,?,?,'practice','in_progress','{}',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", round, user, bank.id(), context);
        jdbc.update("INSERT INTO practice_round_question (round_id,question_id,user_id,payload,extension_digest,updated_at) VALUES (?,'q1',?,'{}',UNHEX(REPEAT('11',32)),CURRENT_TIMESTAMP(3))", round, user);
        jdbc.update("INSERT INTO practice_history_annotation (history_id,question_id,user_id,notes,revision,created_at,updated_at) VALUES (?,'q1',?,'{}',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", round, user);
        jdbc.update("INSERT INTO practice_lifecycle_job (id,user_id,collection_id,context_id,round_id,kind,status,total,created_at,updated_at) VALUES (?,?,?,?,?,'finish','running',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", Ids.uuidV7(), user, bank.id(), context, round);
        uploads.delete(user, jdbc.queryForObject("SELECT library_pack_id FROM practice_collection WHERE id=?", String.class, bank.id()));
        change(user, "group", "delete", bank.groupName(), null, null);
        for (String table : List.of("practice_context", "practice_question_state", "practice_round", "practice_round_question", "practice_history_annotation", "practice_lifecycle_job")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, user)).isZero();
        }
        assertThat(quotas.usage(user).storage().usedBytes()).isZero();
    }

    private PracticeDirectoryService.Result change(String owner, String target, String action, String path, String name, String group) {
        return library.manage(owner, new PracticeDirectoryService.Change("bank", target, action, path, name, group, mapper.revision(owner)));
    }
    private void update(String owner, String id, String name, String group) { change(owner, "item", "update", id, name, group); }
    private cloud.treenewbee.quizforge.testsupport.QueuedPracticeImports.ImportedCollection imported(String owner, String type) throws Exception {
        return imports.importArchive(owner, "test.qbank", new ByteArrayInputStream(ImportArchives.zip(
                ImportArchives.files(List.of(type), "bank"))), ImportArchives.GROUP).getFirst();
    }

    private String user() { return auth.register(UUID.randomUUID() + "@example.test", "管理测试", "password123", "test").userId(); }
    private static Path temporary() {
        try {
            Path path = Files.createTempDirectory("qf-library-test-");
            try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")); }
            catch (UnsupportedOperationException ignored) { /* Windows 使用 ACL。 */ }
            return path;
        }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
