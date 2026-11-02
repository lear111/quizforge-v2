package cloud.treenewbee.quizforge.library;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.quota.application.PrivateCollectionService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
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
class LibraryIntegrationTest extends MySqlTestBase {
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

    @Autowired LibraryService library;
    @Autowired LibraryMapper mapper;
    @Autowired LibraryExportService exports;
    @Autowired LibraryExportMapper exportMapper;
    @Autowired ImportService imports;
    @Autowired PracticeReadService reads;
    @Autowired QuotaService quotas;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired BlobStore blobs;
    @Autowired JsonMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @org.springframework.beans.factory.annotation.Value("${qf.security.allowed-origin}") String origin;
    @MockitoSpyBean PrivateCollectionService deletion;
    String user;

    @BeforeEach void prepare() { user = user(); }
    @AfterEach void restore() { reset(deletion); }
    @AfterAll static void stop() { RUNNER.stop(); }

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
        var result = imports.importArchive(user, "another.qbank", new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        assertThat(result.title()).isEqualTo("单选题（2）");
    }

    @Test void uuidIsUniqueWithinGroupButCanExistAcrossGroupsAndAfterDeletion() throws Exception {
        var first = imported(user, "单选题");
        assertThatThrownBy(() -> imported(user, "单选题")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("DUPLICATE_BANK_UUID"));
        update(user, first.id(), "根目录实例", null);
        var second = imported(user, "单选题");
        assertThat(second.bankId()).isEqualTo(first.bankId());
        assertThatThrownBy(() -> update(user, first.id(), "另一显示名称", second.groupName()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("DUPLICATE_BANK_UUID"));
        library.deleteOne(user, second.id());
        assertThat(imported(user, "单选题").id()).isNotEqualTo(second.id());
    }

    @Test void sameUuidDifferentVersionCannotEnterSameGroup() throws Exception {
        imported(user, "单选题");
        var files = ImportArchives.files(List.of("单选题"), "bank");
        ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> bank.put("description", "作者的新版本"));
        assertThatThrownBy(() -> imports.importArchive(user, "new.qbank", new ByteArrayInputStream(ImportArchives.zip(files))))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("DUPLICATE_BANK_UUID");
                    assertThat(error.getMessage()).contains("其他版本");
                });
        assertThat(mapper.items(user)).hasSize(1);
    }

    @Test void concurrentDuplicateImportsHaveOneWinnerWithoutExtraInstanceOrCharge() throws Exception {
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
                    .containsExactlyInAnyOrder("ok", "DUPLICATE_BANK_UUID");
        }
        assertThat(mapper.items(user)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=?", Integer.class, user)).isEqualTo(1);
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
                    library.manage(user, new LibraryService.Change("bank", "item", "update", bank.id(), name, name, revision));
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

    @Test void ownershipIsEnforcedForManagementAndExport() throws Exception {
        var bank = imported(user, "单选题");
        String other = user();
        assertThatThrownBy(() -> update(other, bank.id(), "越权", null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> exports.export(other, new LibraryExportService.Request(bank.id(), null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status().value()).isEqualTo(404));
        assertThat(mapper.item(user, bank.id()).orElseThrow().title()).isEqualTo(bank.title());
    }

    @Test void realHttpUsesSessionIdentityValidatesRevisionAndHandsDownloadToNginx() throws Exception {
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
            var exportRequest = java.net.http.HttpRequest.newBuilder(uri.resolve("/api/library/export"))
                    .header("Origin", origin).header("Content-Type", "application/json")
                    .header("Cookie", "__Host-qf_session=" + session.token());
            var denied = http.send(exportRequest.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("bank", foreign.id())))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(404);
            var own = imported(session.userId(), "单选题");
            var download = http.send(exportRequest.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("bank", own.id())))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.headers().firstValue("X-Accel-Redirect").orElseThrow()).matches("/_qf_exports/[0-9a-f-]{36}\\.qbank");
            assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(download.body()).isEmpty();
        }
    }

    @Test void groupDeletionKeepsCommittedBanksAndCanResumeAfterFailure() throws Exception {
        imported(user, "单选题");
        imported(user, "多选题");
        var members = mapper.items(user);
        doThrow(new IllegalStateException("模拟第二本删除失败")).when(deletion).delete(user, members.get(1).id());
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
        change(user, "group", "delete", bank.groupName(), null, null);
        for (String table : List.of("practice_context", "practice_question_state", "practice_round", "practice_round_question", "practice_history_annotation", "practice_lifecycle_job")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, user)).isZero();
        }
        assertThat(quotas.usage(user).storage().usedBytes()).isZero();
    }

    @Test void singleAndGroupExportsReallyReimportAllFiveTypesWithoutPracticeData() throws Exception {
        var result = imports.importArchive(user, "five.qbank", new ByteArrayInputStream(ImportArchives.zip(ImportArchives.files(ImportArchives.TYPES, "group"))));
        var single = exports.export(user, new LibraryExportService.Request(result.getFirst().id(), null));
        try (var input = Files.newInputStream(single.path())) {
            assertThat(imports.importArchive(user(), "single.qbank", input)).hasSize(1);
        }
        var group = exports.export(user, new LibraryExportService.Request(null, ImportArchives.GROUP));
        try (ZipFile zip = new ZipFile(group.path().toFile())) {
            assertThat(zip.stream().map(entry -> entry.getName()).toList()).noneMatch(path -> path.contains("practice_") || path.contains("whiteboard") || path.contains("history"));
        }
        try (var input = Files.newInputStream(group.path())) {
            assertThat(imports.importArchive(user(), "group.qbank", input)).hasSize(5);
        }
        assertThat(group.warnings()).isEmpty();
    }

    @Test void rawAuthorValuesOmittedQuestionsAndNetworkAudioSurviveExport() throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "bank");
        String url = "https://audio.example.org/%E5%90%AC%E5%8A%9B.mp3?version=1";
        ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> {
            bank.put("listening", Map.of("materials", List.of(Map.of("id", "audio", "title", "网络材料", "url", url))));
            @SuppressWarnings("unchecked") var first = (Map<String, Object>) ((List<?>) bank.get("questions")).getFirst();
            var omitted = new LinkedHashMap<>(first);
            omitted.put("id", "omitted");
            bank.put("questions", List.of(first, omitted));
        });
        var bank = imports.importArchive(user, "raw.qbank", new ByteArrayInputStream(ImportArchives.zip(files))).getFirst();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_question WHERE bank_id=?", Integer.class, bank.bankId())).isEqualTo(1);
        var exported = exports.export(user, new LibraryExportService.Request(bank.id(), null));
        try (ZipFile zip = new ZipFile(exported.path().toFile())) {
            var entry = zip.stream().filter(item -> item.getName().startsWith("question-banks/") && item.getName().endsWith("/bank.json")).findFirst().orElseThrow();
            var document = json.readTree(zip.getInputStream(entry));
            assertThat(document.path("id").asString()).isEqualTo(ImportArchives.BANK_IDS.get("单选题"));
            assertThat(document.path("questions").size()).isEqualTo(2);
            assertThat(document.path("listening").path("materials").get(0).path("url").asString()).isEqualTo(url);
        }
        try (var input = Files.newInputStream(exported.path())) { assertThat(imports.importArchive(user(), "again.qbank", input)).hasSize(1); }
    }

    @Test void catalogOriginCanExportCurrentVersion() throws Exception {
        var bank = imported(user, "判断题");
        jdbc.update("UPDATE practice_collection SET source=JSON_OBJECT('type','catalog'),source_pack_id=? WHERE id=?", Ids.uuidV7(), bank.id());
        var exported = exports.export(user, new LibraryExportService.Request(bank.id(), null));
        try (var input = Files.newInputStream(exported.path())) { assertThat(imports.importArchive(user(), "catalog.qbank", input)).hasSize(1); }
    }

    @Test void movingToNewGroupRewritesOnlyRequiredBindingsAndReimports() throws Exception {
        var bank = imported(user, "单选题");
        update(user, bank.id(), "显示名", "新分组");
        var exported = exports.export(user, new LibraryExportService.Request(null, "新分组"));
        assertThat(exported.warnings()).anyMatch(message -> message.contains("重新审核"));
        try (var input = Files.newInputStream(exported.path())) {
            assertThat(imports.importArchive(user(), "moved.qbank", input)).hasSize(1);
        }
    }

    @Test void legacyDuplicateUuidGroupRefusesWholeExportWithSingleBankAdvice() throws Exception {
        var bank = imported(user, "单选题");
        jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,group_name,source,created_at) VALUES (?,?,?,?,?,JSON_OBJECT(),CURRENT_TIMESTAMP(3))",
                Ids.uuidV7(), user, bank.bankId(), "已有副本", bank.groupName());
        assertThatThrownBy(() -> exports.export(user, new LibraryExportService.Request(null, bank.groupName())))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("DUPLICATE_BANK_UUID");
                    assertThat(error.getMessage()).contains("逐本导出");
                });
    }

    @Test void legacyExportWarnsAndHasStableDerivedUuid() throws Exception {
        var bank = imported(user, "单选题");
        var original = exportMapper.document(user, bank.id()).orElseThrow();
        jdbc.update("DELETE FROM content_bank_document WHERE bank_id=?", bank.bankId());
        try {
            var first = exports.export(user, new LibraryExportService.Request(bank.id(), null));
            var second = exports.export(user, new LibraryExportService.Request(bank.id(), null));
            assertThat(first.warnings()).anyMatch(message -> message.contains("未引用题目无法恢复"));
            assertThat(exportedUuid(first.path())).isEqualTo(exportedUuid(second.path()))
                    .isEqualTo(UUID.nameUUIDFromBytes(("qf-legacy-bank:" + bank.bankId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            try (var input = Files.newInputStream(first.path())) { assertThat(imports.importArchive(user(), "legacy.qbank", input)).hasSize(1); }
        } finally {
            mapper.preserve(bank.bankId(), original.authorJson(), original.authorBindings());
        }
    }

    @Test void reimportOfSharedLegacyContentBackfillsAuthorDocumentWithoutChangingBankId() throws Exception {
        var first = imported(user, "单选题");
        var original = exportMapper.document(user, first.id()).orElseThrow();
        jdbc.update("DELETE FROM content_bank_document WHERE bank_id=?", first.bankId());
        try {
            var second = imported(user(), "单选题");
            assertThat(second.bankId()).isEqualTo(first.bankId());
            var download = exports.export(user, new LibraryExportService.Request(first.id(), null));
            assertThat(exportedUuid(download.path())).isEqualTo(ImportArchives.BANK_IDS.get("单选题"));
            assertThat(download.warnings()).isEmpty();
        } finally {
            mapper.preserve(first.bankId(), original.authorJson(), original.authorBindings());
        }
    }

    @Test void overLimitDeletesPartialTemporaryFileAndDoesNotTouchTraffic() throws Exception {
        var bank = imported(user, "单选题");
        var small = new LibraryExportService(mapper, exportMapper, blobs, json, Clock.systemUTC(), ROOT.toString(), 100);
        assertThatThrownBy(() -> small.export(user, new LibraryExportService.Request(bank.id(), null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status().value()).isEqualTo(413));
        try (var paths = Files.list(ROOT.resolve("exports"))) { assertThat(paths.map(path -> path.getFileName().toString()).toList()).noneMatch(name -> name.endsWith(".tmp")); }
        assertThat(quotas.usage(user).dailyDownload().usedBytes()).isZero();
    }

    private String exportedUuid(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.stream().filter(item -> item.getName().startsWith("question-banks/") && item.getName().endsWith("/bank.json")).findFirst().orElseThrow();
            return json.readTree(zip.getInputStream(entry)).path("id").asString();
        }
    }

    private LibraryService.Result change(String owner, String target, String action, String path, String name, String group) {
        return library.manage(owner, new LibraryService.Change("bank", target, action, path, name, group, mapper.revision(owner)));
    }
    private void update(String owner, String id, String name, String group) { change(owner, "item", "update", id, name, group); }
    private ImportService.ImportedCollection imported(String owner, String type) throws Exception {
        return imports.importArchive(owner, "test.qbank", new ByteArrayInputStream(ImportArchives.zip(ImportArchives.files(List.of(type), "bank")))).getFirst();
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
