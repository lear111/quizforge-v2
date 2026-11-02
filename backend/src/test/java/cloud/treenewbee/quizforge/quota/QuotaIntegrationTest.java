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
    @Autowired QuotaService quotas;
    @Autowired PrivateCollectionService deletion;
    @Autowired DraftService drafts;
    @Autowired ImportService imports;
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
    @Autowired SqlGate gate;
    final List<String> users = new ArrayList<>();
    final HttpClient http = HttpClient.newHttpClient();
    record User(String id, String cookie) {}
    record Archive(byte[] bytes, long size) {}

    @BeforeEach void setup() {
        clock.now.set(Instant.parse("2026-11-01T15:59:59Z"));
        reset(runner); gate.clear();
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
        gate.clear();
        for (String user : users) {
            for (String id : jdbc.queryForList("SELECT id FROM practice_collection WHERE user_id=? AND deleted_at IS NULL", String.class, user)) deletion.delete(user, id);
            jdbc.update("DELETE FROM quota_storage_item WHERE user_id=?", user);
            jdbc.update("DELETE FROM quota_adjustment WHERE user_id=? OR actor_id=?", user, user);
            jdbc.update("DELETE FROM quota_account WHERE user_id=?", user);
            jdbc.update("DELETE FROM practice_request_receipt WHERE user_id=?", user);
            jdbc.update("DELETE FROM practice_collection WHERE user_id=?", user);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", user);
            jdbc.update("DELETE FROM account_user WHERE id=?", user);
        }
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

    @Test void importChargesExactExpandedBytesAndDeletingReleasesOnlyThatInstance() throws Exception {
        var user = user(false); var archive = archive();
        var first = importOne(user, archive); var second = importOne(user, archive);
        assertThat(first.bankId()).isEqualTo(second.bankId());
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(archive.size() * 2);
        deletion.delete(user.id(), first.id());
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(archive.size());
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

    @Test void groupWithSharedExtensionChargesAnExpandedCopyForEachBank() throws Exception {
        var user = user(false); var files = ImportArchives.files(List.of("单选题"),"group");
        String original = ImportArchives.bank("单选题")+"/bank.json";
        String second = ImportArchives.bank("第二本")+"/bank.json";
        files.put(second,files.get(original));
        ImportArchives.edit(files,second,bank -> bank.put("id",Ids.uuidV7()));
        long common = files.entrySet().stream().filter(e -> e.getKey().startsWith("extensions/") || e.getKey().equals("quizforge-package.json")).mapToLong(e -> e.getValue().length).sum();
        long bankBytes = files.entrySet().stream().filter(e -> e.getKey().startsWith("question-banks/")).mapToLong(e -> e.getValue().length).sum();
        long expected = common*2 + bankBytes;
        var result = imports.importArchive(user.id(),"group.qbank",new ByteArrayInputStream(ImportArchives.zip(files)));
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
        var scenario = gate.arm(); var ready = scenario.ready; var release = scenario.release;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> attemptImport(user, archive)); var b = workers.submit(() -> attemptImport(user, archive));
            try { assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
            var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(v -> v.equals("ok")).count()).isEqualTo(1);
            assertThat(results.stream().filter(v -> v.equals("STORAGE_QUOTA_EXCEEDED")).count()).isEqualTo(1);
        } finally { release.countDown(); gate.clear(); }
        assertThat(scenario.connections).hasSize(2);
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
        var freshService = new QuotaService(quotaMapper,userMapper,clock,json,audit);
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=? AND category<>'import'",Long.class,user.id())).isZero();
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
        String boundary="quota-boundary";
        byte[] begin=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.qbank\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(begin),HttpRequest.BodyPublishers.ofByteArray(archive.bytes()),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/library/import")).header("Origin",ORIGIN).header("Cookie",user.cookie()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json.readTree(response.body()).path("error").path("code").asString()).isEqualTo("STORAGE_QUOTA_EXCEEDED");
        quotas.adjust(admin.id(),user.id(),archive.size(),null);
        var collection=importOne(user,archive);
        assertThat(request("DELETE","/api/collections/bank/"+collection.id(),other.cookie(),null).statusCode()).isEqualTo(404);
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
        return new Archive(ImportArchives.zip(files), files.values().stream().mapToLong(a -> a.length).sum());
    }
    ImportService.ImportedCollection importOne(User user,Archive archive) {
        return imports.importArchive(user.id(),"test.qbank",new ByteArrayInputStream(archive.bytes())).getFirst();
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
        @Bean SqlGate quotaSqlGate() { return new SqlGate(); }
    }
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type = org.apache.ibatis.executor.Executor.class,
            method = "update", args = {org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static class SqlGate implements org.apache.ibatis.plugin.Interceptor {
        static class Scenario {
            final CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
            final Set<Long> connections = ConcurrentHashMap.newKeySet();
        }
        volatile Scenario scenario;
        Scenario arm() { return scenario = new Scenario(); }
        void clear() { var old = scenario; scenario = null; if(old != null)old.release.countDown(); }
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            var current = scenario;
            if (current != null && ((org.apache.ibatis.mapping.MappedStatement)call.getArgs()[0]).getId().endsWith(".BankMapper.insert")) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                var connection = ((org.apache.ibatis.executor.Executor)call.getTarget()).getTransaction().getConnection();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next(); current.connections.add(rows.getLong(1));
                }
                current.ready.countDown(); assertThat(current.release.await(15,TimeUnit.SECONDS)).isTrue();
            }
            return call.proceed();
        }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T15:59:59Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
