package cloud.treenewbee.quizforge.quota;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.DraftService;
import cloud.treenewbee.quizforge.quota.application.*;
import cloud.treenewbee.quizforge.rules.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(QuotaIntegrationTest.Configuration.class)
@Timeout(90)
class QuotaIntegrationTest extends MySqlTestBase {
    static final String ORIGIN = "https://quota.example.test";
    static final Path ROOT = temporary();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("qf.storage.blob-dir", () -> ROOT.resolve("blobs").toString());
        registry.add("qf.import.temp-dir", () -> ROOT.resolve("uploads").toString());
        registry.add("qf.security.allowed-origin", () -> ORIGIN);
    }
    @Autowired cloud.treenewbee.quizforge.quota.infrastructure.PrivateRevisionMapper privateRevisions;
    @Autowired QuotaService quotas;
    @Autowired cloud.treenewbee.quizforge.content.application.ImportUploadService uploads;
    @Autowired PrivateCollectionService deletion;
    @Autowired DraftService drafts;
    @Autowired cloud.treenewbee.quizforge.testsupport.QueuedPracticeImports imports;
    @Autowired AuthService auth;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired cloud.treenewbee.quizforge.admin.application.AdminAuditService audit;
    @Autowired MutableClock clock;
    @Autowired cloud.treenewbee.quizforge.quota.infrastructure.QuotaMapper quotaMapper;
    @Autowired cloud.treenewbee.quizforge.identity.infrastructure.UserMapper userMapper;
    @Value("${local.server.port}") int port;
    @MockitoBean RuleRunnerClient runner;
    @Autowired LedgerReadGate ledgerGate;
    final List<String> users = new ArrayList<>();
    final HttpClient http = HttpClient.newHttpClient();
    record User(String id, String cookie) {}
    record Archive(byte[] bytes, long size) {}

    @BeforeEach void setup() {
        clock.now.set(Instant.parse("2026-11-01T15:59:59Z"));
        reset(runner);
        ledgerGate.clear();
        when(runner.background(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        when(runner.execute(anyString(), any(), anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String op = call.getArgument(0);
            Map<?, ?> input = call.getArgument(2);
            Object result = switch (op) {
                case "capabilities" -> Map.of("canAiGrade", false, "canOutlineItems", false);
                case "validateBank" -> Map.of("valid", true);
                case "scoreBatch" -> Map.of("scores", ((List<?>) input.get("questions")).stream().map(q -> Map.of("maxScore", 1)).toList());
                default -> throw new AssertionError(op);
            };
            return RuleResult.success(json.valueToTree(result));
        });
    }

    @AfterEach void cleanup() {
        ledgerGate.clear();

        for (String user : users) {
            for (String id : jdbc.queryForList("SELECT id FROM practice_collection WHERE user_id=? AND deleted_at IS NULL", String.class, user)) deletion.delete(user, id);
            for (String pack : jdbc.queryForList("SELECT id FROM catalog_pack WHERE owner_id=? AND status='active'", String.class, user)) uploads.delete(user, pack);
            jdbc.update("DELETE FROM quota_storage_item WHERE user_id=?", user);
            jdbc.update("DELETE FROM quota_adjustment WHERE user_id=? OR actor_id=?", user, user);
            jdbc.update("DELETE FROM quota_account WHERE user_id=?", user);
            jdbc.update("DELETE FROM practice_request_receipt WHERE user_id=?", user);
            jdbc.update("DELETE FROM practice_collection WHERE user_id=?", user);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", user);
            jdbc.update("DELETE FROM account_user WHERE id=?", user);
        }
    }

    @Test void firstLedgerItemsForDifferentUsersDoNotGapLockEachOther() throws Exception {
        var first = user(false);
        var second = user(false);
        quotas.usage(first.id());
        quotas.usage(second.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id BETWEEN ? AND ?",
                Integer.class, first.id(), second.id())).isZero();
        var scenario = ledgerGate.arm();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> tx.executeWithoutResult(status -> quotas.setStorage(first.id(), "private_pack", "first", null, 17)));
            var b = workers.submit(() -> tx.executeWithoutResult(status -> quotas.setStorage(second.id(), "private_pack", "second", null, 23)));
            try {
                assertThat(scenario.ready.await(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                scenario.release.countDown();
            }
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            ledgerGate.clear();
        }
        assertThat(quotas.usage(first.id()).storage().usedBytes()).isEqualTo(17);
        assertThat(quotas.usage(second.id()).storage().usedBytes()).isEqualTo(23);
    }

    @Test void defaultsOverridesNullAndLoweringDoNotDeleteOrResetUsage() {
        var admin = user(true); var user = user(false);
        assertThat(quotas.usage(user.id()).storage().limitBytes()).isEqualTo(256L * 1024 * 1024);
        assertThat(quotas.usage(user.id()).dailyDownload().limitBytes()).isEqualTo(256L * 1024 * 1024);
        tx.executeWithoutResult(s -> quotas.setStorage(user.id(), "resource", "test-image", null, 100));
        quotas.adjust(admin.id(), user.id(), 20L, 10L);
        assertThat(quotas.usage(user.id()).storage()).isEqualTo(new QuotaService.Amount(100, 20, 20L));
        tx.executeWithoutResult(s -> quotas.setStorage(user.id(), "resource", "test-image", null, 50));
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(50);
        quotas.adjust(admin.id(), user.id(), null, null);
        assertThat(quotas.usage(user.id()).storage()).isEqualTo(new QuotaService.Amount(50, 268435456, null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_adjustment WHERE user_id=?", Integer.class, user.id())).isEqualTo(2);
    }

    @Test void nullableOverridesFollowPersistedSiteDefault() {
        var user = user(false); var admin = user(true);
        try {
            jdbc.update("UPDATE quota_defaults SET storage_bytes=1000,daily_download_bytes=2000 WHERE id=1");
            assertThat(quotas.usage(user.id()).storage().limitBytes()).isEqualTo(1000);
            quotas.adjust(admin.id(), user.id(), 0L, 0L);
            jdbc.update("UPDATE quota_defaults SET storage_bytes=3000,daily_download_bytes=4000 WHERE id=1");
            assertThat(quotas.usage(user.id()).storage().limitBytes()).isZero();
            quotas.adjust(admin.id(), user.id(), null, null);
            assertThat(quotas.usage(user.id()).dailyDownload().limitBytes()).isEqualTo(4000);
        } finally { jdbc.update("UPDATE quota_defaults SET storage_bytes=268435456,daily_download_bytes=268435456 WHERE id=1"); }
    }

    @Test void rawUploadsChargeOriginalBytesAndReleaseAfterBothResourceAndInstanceAreDeleted() throws Exception {
        var user = user(false); var archive = archive();
        var first = importOne(user, archive); var second = importOne(user, archive);
        assertThat(first.bankId()).isEqualTo(second.bankId());
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(archive.size() * 2);
        String firstPack = pack(first.id()), secondPack = pack(second.id());
        uploads.delete(user.id(), firstPack);
        deletion.delete(user.id(), first.id());
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(archive.size());
        uploads.delete(user.id(), secondPack);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_bank WHERE id=?", Integer.class, first.bankId())).isEqualTo(1);
        deletion.delete(user.id(), second.id());
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
    }

    @Test void sharedPhysicalContentIsChargedIndependentlyForEachUser() throws Exception {
        var a = user(false); var b = user(false); var archive = archive();
        var first = importOne(a, archive); var second = importOne(b, archive);
        assertThat(first.bankId()).isEqualTo(second.bankId());
        assertThat(quotas.usage(a.id()).storage().usedBytes()).isEqualTo(archive.size());
        assertThat(quotas.usage(b.id()).storage().usedBytes()).isEqualTo(archive.size());
        deletion.delete(a.id(), first.id());
        assertThat(quotas.usage(b.id()).storage().usedBytes()).isEqualTo(archive.size());
    }

    @Test void groupWithSharedExtensionChargesOneOriginalAndRetainsAllInstanceReferences() throws Exception {
        var user = user(false); var files = ImportArchives.files(List.of("单选题"),"group");
        String original = ImportArchives.bank("单选题")+"/bank.json";
        String second = ImportArchives.bank("第二本")+"/bank.json";
        files.put(second,files.get(original));
        ImportArchives.edit(files,second,bank -> bank.put("id",Ids.uuidV7()));
        byte[] archive = ImportArchives.zip(files);
        long expected = archive.length;
        var result = imports.importArchive(user.id(),"group.qbank",new ByteArrayInputStream(archive));
        assertThat(result).hasSize(2);
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(expected);
    }

    @Test void overQuotaImportRollsBackEveryBusinessRowAndLedger() throws Exception {
        var user = user(false); var admin = user(true); var archive = archive();
        quotas.adjust(admin.id(), user.id(), archive.size() - 1, null);
        long banksBefore = count("content_bank"); long packagesBefore = count("content_extension_package");
        assertQuota(() -> importOne(user, archive), "STORAGE_QUOTA_EXCEEDED");
        assertThat(count("content_bank")).isEqualTo(banksBefore);
        assertThat(count("content_extension_package")).isEqualTo(packagesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE user_id=?", Long.class, user.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=?", Long.class, user.id())).isZero();
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
        try (var files = Files.list(ROOT.resolve("uploads"))) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void concurrentImportsCannotExceedSingleInstanceLimit() throws Exception {
        var user = user(false); var admin = user(true); var archive = archive();
        quotas.adjust(admin.id(), user.id(), archive.size(), null);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> upload = () -> {
                var input = new java.io.FilterInputStream(new ByteArrayInputStream(archive.bytes())) {
                    boolean entered;
                    @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                        if (!entered) {
                            entered = true;
                            ready.countDown();
                            try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                        }
                        return super.read(bytes, offset, length);
                    }
                };
                try { imports.importArchive(user.id(), "concurrent.qbank", input); return "ok"; }
                catch (ApiException error) { return error.code(); }
            };
            var a = workers.submit(upload); var b = workers.submit(upload);
            try { assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "STORAGE_QUOTA_EXCEEDED");
        } finally { release.countDown(); }
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(archive.size());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE user_id=?", Long.class, user.id())).isEqualTo(1);
    }

    @Test void storageBatchChecksNetGrowthAndRollsBackAllItemsOnOverage() {
        var user = user(false); var admin = user(true);
        tx.executeWithoutResult(s -> quotas.setStorage(user.id(),"state","old",null,100));
        quotas.adjust(admin.id(),user.id(),100L,null);
        tx.executeWithoutResult(s -> quotas.setStorageBatch(user.id(),List.of(
                new QuotaService.StorageItem("snapshot","new",null,100),
                new QuotaService.StorageItem("state","old",null,0))));
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(100);
        assertQuota(() -> tx.executeWithoutResult(s -> quotas.setStorageBatch(user.id(),List.of(
                new QuotaService.StorageItem("state","old",null,102),
                new QuotaService.StorageItem("snapshot","new",null,0)))),"STORAGE_QUOTA_EXCEEDED");
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM quota_storage_item WHERE user_id=? AND category='snapshot'",Long.class,user.id())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM quota_storage_item WHERE user_id=? AND category='state'",Long.class,user.id())).isZero();
        quotas.adjust(admin.id(),user.id(),200L,null);
        tx.executeWithoutResult(s -> quotas.setStorage(user.id(),"whiteboard","context:q1",null,100));
        quotas.adjust(admin.id(),user.id(),50L,null);
        tx.executeWithoutResult(s -> quotas.setStorageBatchAndClearPrefix(user.id(),
                List.of(new QuotaService.StorageItem("snapshot","new",null,75)),"whiteboard","context:"));
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(75); // Shrinking is allowed above a newly lowered limit.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=? AND category='whiteboard'",Integer.class,user.id())).isZero();
    }

    @Test void storageAndDownloadRollbackWithCallerFailureAndRequireTransaction() {
        var user = user(false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            quotas.setStorage(user.id(), "resource", "png", null, 10);
            quotas.consumeDownload(user.id(), 20); throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
        assertThat(quotas.usage(user.id()).dailyDownload().usedBytes()).isZero();
        assertThatThrownBy(() -> quotas.consumeDownload(user.id(), 1)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> quotas.setStorage(user.id(), "resource", "png", null, 1)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void downloadLimitResetsAtBeijingMidnightAndSurvivesNewServiceCall() {
        var user = user(false); var admin = user(true);
        quotas.adjust(admin.id(), user.id(), null, 100L);
        tx.executeWithoutResult(s -> quotas.consumeDownload(user.id(), 100));
        var freshService = new QuotaService(quotaMapper,userMapper,clock,json,audit,privateRevisions);
        assertThat(tx.execute(s -> freshService.usage(user.id())).dailyDownload().usedBytes()).isEqualTo(100);
        assertThat(quotas.usage(user.id()).day()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(quotas.usage(user.id()).resetsAt()).isEqualTo(Instant.parse("2026-11-01T16:00:00Z"));
        assertQuota(() -> tx.executeWithoutResult(s -> quotas.consumeDownload(user.id(), 1)), "DOWNLOAD_QUOTA_EXCEEDED");
        clock.now.set(Instant.parse("2026-11-01T16:00:00Z"));
        assertThat(quotas.usage(user.id()).dailyDownload().usedBytes()).isZero();
        tx.executeWithoutResult(s -> quotas.consumeDownload(user.id(), 7));
        assertThat(quotas.usage(user.id()).dailyDownload().usedBytes()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT download_day FROM quota_account WHERE user_id=?", LocalDate.class, user.id())).isEqualTo(LocalDate.parse("2026-11-02"));
    }

    @Test void concurrentDownloadReservationsCannotExceedLimit() throws Exception {
        var user = user(false); var admin = user(true);
        quotas.adjust(admin.id(), user.id(), null, 10L);
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> consume = () -> { start.await(10, TimeUnit.SECONDS); try { tx.executeWithoutResult(s -> quotas.consumeDownload(user.id(), 10)); return "ok"; } catch (ApiException e) { return e.code(); } };
            var a = workers.submit(consume); var b = workers.submit(consume);
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok", "DOWNLOAD_QUOTA_EXCEEDED");
        }
        assertThat(quotas.usage(user.id()).dailyDownload().usedBytes()).isEqualTo(10);
    }

    @Test void deletionRemovesHistoricalSnapshotsWhiteboardsAndReleasesAllOwnedItems() throws Exception {
        var user = user(false); var other = user(false); var collection = importOne(user, archive());
        uploads.delete(user.id(), pack(collection.id()));
        var context = context(user.id(), collection.id()); var round = Ids.uuidV7();
        jdbc.update("INSERT INTO practice_whiteboard (context_id,question_id,user_id,draft,size_bytes,updated_at) VALUES (?, 'q1', ?, '{}', 2, NOW(3))", context, user.id());
        jdbc.update("INSERT INTO practice_round (id,user_id,collection_id,context_id,mode,status,collection_snapshot,created_at,updated_at) VALUES (?,?,?,?,'practice','completed','{}',NOW(3),NOW(3))", round,user.id(),collection.id(),context);
        jdbc.update("INSERT INTO practice_round_question VALUES (?, 'q1', ?, '{\"notes\":[]}', UNHEX(REPEAT('12',32)), NOW(3))",round,user.id());
        tx.executeWithoutResult(s -> { quotas.setStorage(user.id(),"round",round,collection.id(),2); quotas.setStorage(user.id(),"snapshot",round+":q1",collection.id(),13); quotas.setStorage(user.id(),"whiteboard",context+":q1",collection.id(),2); });
        assertThatThrownBy(() -> deletion.delete(other.id(), collection.id())).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE id=?", Integer.class,round)).isEqualTo(1);
        deletion.delete(user.id(), collection.id());
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round_question WHERE round_id=?",Integer.class,round)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_round WHERE id=?",Integer.class,round)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_whiteboard WHERE context_id=?",Integer.class,context)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_context WHERE id=?",Integer.class,context)).isZero();
    }

    @Test void draftsDoNotConsumeQuotaEvenWhenPrivateStorageIsFull() throws Exception {
        var user = user(false); var admin = user(true); var collection = importOne(user, archive());
        String context = context(user.id(), collection.id()); long used = quotas.usage(user.id()).storage().usedBytes();
        quotas.adjust(admin.id(),user.id(),used,null);
        assertThat(drafts.saveDraft(user.id(),context,"q1",0,"\"中文\"",new byte[32])).isEqualTo(1);
        assertThat(drafts.saveDraft(user.id(),context,"q1",1,"\"longer answer\"",new byte[32])).isEqualTo(2);
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(used);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=? AND category NOT IN ('import','private_pack')",Long.class,user.id())).isZero();
    }

    @Test void quotaHttpIsPrivateAdminOnlyAndNormalReadsNeverConsumeTraffic() throws Exception {
        var normal = user(false); var other = user(false); var admin = user(true);
        var get = request("GET","/api/account/quota",normal.cookie(),null);
        assertThat(get.statusCode()).isEqualTo(200); assertThat(get.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(get.body()).path("storage").path("limitBytes").asLong()).isEqualTo(268435456);
        String path = "/api/admin/users/"+other.id()+"/quota";
        assertThat(request("PUT",path,normal.cookie(),"{\"storageBytes\":1,\"dailyDownloadBytes\":2}").statusCode()).isEqualTo(403);
        assertThat(request("PUT",path,admin.cookie(),"{\"storageBytes\":1,\"dailyDownloadBytes\":2}").statusCode()).isEqualTo(200);
        assertThat(quotas.usage(other.id()).storage().limitBytes()).isEqualTo(1);
        assertThat(request("PUT",path,admin.cookie(),"{\"storageBytes\":-1}").statusCode()).isEqualTo(400);
        assertThat(request("PUT",path,admin.cookie(),"{\"storageBytes\":null,\"dailyDownloadBytes\":null}").statusCode()).isEqualTo(200);
        assertThat(request("GET","/api/account/quota",null,null).statusCode()).isEqualTo(401);
        request("GET","/api/catalog",normal.cookie(),null); request("GET","/api/health",normal.cookie(),null);
        assertThat(quotas.usage(normal.id()).dailyDownload().usedBytes()).isZero();
    }

    @Test void publicContentReferenceDoesNotChargePrivateStorage() throws Exception {
        var owner = user(false); var publicUser = user(false); var imported = importOne(owner,archive());
        String id = Ids.uuidV7();
        jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,source_pack_id,created_at) VALUES (?,?,?,'Public','{\"type\":\"public\"}',?,NOW(3))",id,publicUser.id(),imported.bankId(),Ids.uuidV7());
        assertThat(quotas.usage(publicUser.id()).storage().usedBytes()).isZero();
        deletion.delete(publicUser.id(),id);
        assertThat(quotas.usage(owner.id()).storage().usedBytes()).isPositive();
    }

    @Test void importHttpReturnsQuotaErrorAndDeleteHttpIsOwnerScoped() throws Exception {
        var user = user(false); var other = user(false); var admin = user(true); var archive = archive();
        quotas.adjust(admin.id(),user.id(),archive.size()-1,null);
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/library/uploads?filename=test.qbank"))
                .header("Origin",ORIGIN).header("Cookie",user.cookie()).header("Content-Type","application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(archive.bytes())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json.readTree(response.body()).path("error").path("code").asString()).isEqualTo("STORAGE_QUOTA_EXCEEDED");
        quotas.adjust(admin.id(),user.id(),archive.size(),null);
        var collection=importOne(user,archive);
        assertThat(request("DELETE","/api/collections/bank/"+collection.id(),other.cookie(),null).statusCode()).isEqualTo(404);
        uploads.delete(user.id(), pack(collection.id()));
        assertThat(request("DELETE","/api/collections/bank/"+collection.id(),user.cookie(),null).statusCode()).isEqualTo(204);
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
        assertThat(request("DELETE","/api/collections/bank/"+collection.id(),user.cookie(),null).statusCode()).isEqualTo(404);
    }

    User user(boolean admin) {
        var result = auth.register(Ids.uuidV7()+"@quota.test","配额测试","test-password-123",null);
        users.add(result.userId()); if (admin) jdbc.update("UPDATE account_user SET is_admin=TRUE WHERE id=?",result.userId());
        return new User(result.userId(),"__Host-qf_session="+result.token());
    }
    Archive archive() throws Exception {
        var files = ImportArchives.files(List.of("单选题"),"bank");
        ImportArchives.edit(files,ImportArchives.bank("单选题")+"/bank.json",bank -> bank.put("id",Ids.uuidV7()));
        byte[] bytes = ImportArchives.zip(files);
        return new Archive(bytes, bytes.length);
    }
    cloud.treenewbee.quizforge.testsupport.QueuedPracticeImports.ImportedCollection importOne(User user,Archive archive) {
        return imports.importArchive(user.id(),"test.qbank",new ByteArrayInputStream(archive.bytes())).getFirst();
    }
    String pack(String collection) {
        return jdbc.queryForObject("SELECT library_pack_id FROM practice_collection WHERE id=?", String.class, collection);
    }
    String attemptImport(User user,Archive archive) { try { importOne(user,archive); return "ok"; } catch(ApiException e) { return e.code(); } }
    String context(String user,String collection) {
        String id = Ids.uuidV7(); jdbc.update("INSERT INTO practice_context (id,user_id,collection_id,kind,scope_key,created_at,updated_at) VALUES (?,?,?,'practice','default',NOW(3),NOW(3))",id,user,collection); return id;
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    static void assertQuota(Runnable call,String code) { assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(code)); }
    HttpResponse<String> request(String method,String path,String cookie,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin",ORIGIN).header("Content-Type","application/json");
        if(cookie!=null)b.header("Cookie",cookie);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static Path temporary() { try { return Files.createTempDirectory("quota-test-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @TestConfiguration static class Configuration {
        @Bean @Primary MutableClock quotaClock() { return new MutableClock(); }
        @Bean LedgerReadGate ledgerReadGate() { return new LedgerReadGate(); }
    }
    /** 两个事务都读完明细后才允许更新，稳定覆盖不存在明细的间隙锁回归。 */
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type = org.apache.ibatis.executor.Executor.class,
            method = "query", args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class,
            org.apache.ibatis.session.RowBounds.class, org.apache.ibatis.session.ResultHandler.class}))
    static class LedgerReadGate implements org.apache.ibatis.plugin.Interceptor {
        static class Scenario {
            final CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        }
        volatile Scenario current;
        Scenario arm() { return current = new Scenario(); }
        void clear() {
            var old = current;
            current = null;
            if (old != null) old.release.countDown();
        }
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            Object result = call.proceed();
            var scenario = current;
            if (scenario != null && ((org.apache.ibatis.mapping.MappedStatement) call.getArgs()[0]).getId().endsWith(".QuotaMapper.item")) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                scenario.ready.countDown();
                assertThat(scenario.release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T15:59:59Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
