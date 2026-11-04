package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportQueueJobs;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.library.application.MyLibraryService;
import cloud.treenewbee.quizforge.practice.application.LibraryPracticeService;
import cloud.treenewbee.quizforge.practice.management.PracticeDirectoryService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/** 真实 MySQL / runner 验证资源与练习实例边界；HTTP 线程不使用测试事务。 */
@Timeout(180)
class MyLibraryIntegrationTest extends MySqlTestBase {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { CatalogIntegrationTest.properties(registry); }
    @Autowired ImportUploadService uploads;
    @Autowired ImportQueueJobs queue;
    @Autowired LibraryPracticeService practice;
    @Autowired MyLibraryService library;
    @Autowired PracticeDirectoryService directory;
    @Autowired QuotaService quotas;
    @Autowired AuthService auth;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired cloud.treenewbee.quizforge.admin.infrastructure.ImportOverviewMapper stats;
    @Autowired cloud.treenewbee.quizforge.content.infrastructure.BlobStore blobs;
    @MockitoSpyBean RuleRunnerClient runner;
    @MockitoSpyBean cloud.treenewbee.quizforge.content.infrastructure.QuestionMapper questions;
    @MockitoSpyBean cloud.treenewbee.quizforge.content.application.ImportService ingest;
    @Value("${local.server.port}") int port;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    record User(String id, String cookie) {}
    record Fixture(User user, String pack, String bank, long bytes) {}

    @BeforeEach
    void prepare() {
        jdbc.update("UPDATE content_import_slot SET job_id=NULL,lease_until=NULL");
        jdbc.update("DELETE FROM content_import_job");
        jdbc.update("DELETE FROM admin_extension_review");
        // 本类验证资源 API，前台流量退避已由队列的可控时钟测试覆盖。
        doReturn(false).when(runner).foregroundRecentlyActive();
    }

    @Test
    void approvedImportCreatesIndependentNamedInstancesWithoutRulesParsingOrQuestionReads() throws Exception {
        var f = ready();
        clearInvocations(runner, ingest, questions);
        var first = create(f);
        var second = create(f);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(second.title()).isEqualTo(first.title() + "（2）");
        assertThat(first.bankId()).isEqualTo(second.bankId());
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
        assertThat(jdbc.queryForObject("SELECT instance_count FROM quota_private_revision WHERE pack_id=?", Long.class, f.pack())).isEqualTo(2);
        verifyNoInteractions(runner, ingest, questions);
    }

    @Test
    void pendingRejectedAndRevokedResourcesCannotCreateInstances() throws Exception {
        User owner = user(false);
        var a = receive(owner, archive());
        assertApi(() -> practice.create(owner.id(), a.packId(), 1, List.of(Ids.uuidV7()), null, true), "PACK_NOT_APPROVED");
        queue.runOne();
        jdbc.update("UPDATE admin_extension_review SET status='pending'");
        var bank = library.detail(owner.id(), a.packId()).revisions().getFirst().banks().getFirst();
        assertApi(() -> practice.create(owner.id(), a.packId(), 1, List.of(bank.bankUuid()), null, true), "PACK_NOT_APPROVED");
        jdbc.update("UPDATE admin_extension_review r JOIN content_question q ON q.extension_digest=r.digest SET r.status='revoked' WHERE q.bank_id=?", bank.bankId());
        assertApi(() -> practice.create(owner.id(), a.packId(), 1, List.of(bank.bankUuid()), null, true), "PACK_NOT_APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE user_id=?", Integer.class, owner.id())).isZero();
    }

    @Test
    void deletingResourceKeepsInstancesContentAndQuotaUntilLastInstanceIsDeleted() throws Exception {
        var f = ready();
        var first = create(f);
        var second = create(f);
        uploads.delete(f.user().id(), f.pack());
        assertThat(library.mine(f.user().id(), null)).isEmpty();
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
        assertThat(send(f.user(), "/api/collections/bank/" + first.id(), "GET", null).statusCode()).isEqualTo(200);
        directory.deleteOne(f.user().id(), first.id());
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
        directory.deleteOne(f.user().id(), second.id());
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isZero();
    }

    @Test
    void deletingInstancesFirstKeepsChargeWhileResourceExists() throws Exception {
        var f = ready();
        var first = create(f);
        directory.deleteOne(f.user().id(), first.id());
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
        uploads.delete(f.user().id(), f.pack());
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isZero();
    }

    @Test
    void concurrentImportsAndResourceDeletionAreSerializedWithoutLostReferences() throws Exception {
        var f = ready();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var first = threads.submit(() -> { gate.await(); return create(f); });
            var second = threads.submit(() -> { gate.await(); return create(f); });
            gate.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.id()).isNotEqualTo(b.id());
            assertThat(a.title()).isNotEqualTo(b.title());
            var deletes = new CountDownLatch(1);
            var resource = threads.submit(() -> { deletes.await(); uploads.delete(f.user().id(),f.pack()); return true; });
            var instance = threads.submit(() -> { deletes.await(); directory.deleteOne(f.user().id(),a.id()); return true; });
            deletes.countDown();
            resource.get(15,TimeUnit.SECONDS);
            instance.get(15,TimeUnit.SECONDS);
            assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
            directory.deleteOne(f.user().id(),b.id());
            assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isZero();
        }
    }

    @Test
    void originalDownloadIsAuthorizedAndUsesNginxWithoutReadingContentInJava() throws Exception {
        var f = ready();
        var response = send(f.user(), archivePath(f.pack()), "GET", null);
        assertThat(response.statusCode()).isEqualTo(200);
        String hash = jdbc.queryForObject("SELECT LOWER(HEX(blob_sha256)) FROM catalog_pack_revision WHERE pack_id=?",String.class,f.pack());
        assertThat(response.headers().firstValue("X-Accel-Redirect")).hasValue("/_qf_blobs/" + hash.substring(0,2) + "/" + hash.substring(2,4) + "/" + hash);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment");
        try (var input = blobs.open(hash)) { assertThat(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).isEqualTo(java.util.HexFormat.of().parseHex(hash)); }
        assertThat(send(user(false), archivePath(f.pack()),"GET",null).statusCode()).isEqualTo(404);
    }

    @Test
    void idorRejectsPrivateListingDetailsImportReuploadPublishAndDelete() throws Exception {
        var f = ready();
        var stranger = user(false);
        assertThat(library.mine(stranger.id(), null)).isEmpty();
        assertThat(send(stranger,"/api/library/packs/" + f.pack(),"GET",null).statusCode()).isEqualTo(404);
        assertThat(send(stranger,"/api/library/packs/" + f.pack() + "/practice","POST",Map.of("revision",1,"banks",List.of(f.bank()))).statusCode()).isEqualTo(404);
        assertApi(() -> uploads.publishPrivate(stranger.id(), f.pack(),1,info()),"PACK_NOT_FOUND");
        assertApi(() -> uploads.delete(stranger.id(),f.pack()),"PACK_NOT_FOUND");
        assertApi(() -> uploads.receivePrivate(stranger.id(),f.pack(),"retry.qbank",1,new ByteArrayInputStream(new byte[1])),"PACK_NOT_FOUND");
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
    }

    @Test
    void rejectedReuploadPreservesBothResultsAndBothPrivateVersionCharges() throws Exception {
        var owner = user(true);
        var bad = receive(owner,new byte[]{1,2,3});
        queue.runOne();
        var bytes = archive();
        var good = uploads.receivePrivate(owner.id(),bad.packId(),"new.qbank",bytes.length,new ByteArrayInputStream(bytes));
        queue.runOne();
        var revisions = library.detail(owner.id(),bad.packId()).revisions();
        assertThat(revisions).extracting(MyLibraryService.Version::status).containsExactly("approved","rejected");
        assertThat(good.revision()).isEqualTo(2);
        assertThat(quotas.usage(owner.id()).storage().usedBytes()).isEqualTo(bytes.length+3);
        uploads.delete(owner.id(),bad.packId());
        assertThat(quotas.usage(owner.id()).storage().usedBytes()).isZero();
    }

    @Test
    void privatePublishingReusesExactOriginalAndPublicVersionStillNeedsReview() throws Exception {
        var f = ready();
        // 私人包的所有者改为普通用户，验证公开版本不会绕过版本审核。
        jdbc.update("UPDATE account_user SET is_admin=FALSE WHERE id=?",f.user().id());
        clearInvocations(runner);
        var published = uploads.publishPrivate(f.user().id(), f.pack(),1,info());
        verifyNoInteractions(runner);
        assertThat(jdbc.queryForList("SELECT HEX(blob_sha256) FROM catalog_pack_revision WHERE pack_id IN (?,?)",String.class,f.pack(),published.packId())).containsOnly(jdbc.queryForObject("SELECT HEX(blob_sha256) FROM catalog_pack_revision WHERE pack_id=?",String.class,f.pack()));
        assertThat(quotas.usage(f.user().id()).storage().usedBytes()).isEqualTo(f.bytes());
        queue.runOne();
        assertThat(uploads.reviewState(f.user().id(),published.packId(),1).status()).isEqualTo("pending");
        assertThat(library.mine(f.user().id(),null)).hasSize(2);
    }

    @Test
    void historicalResourceReportsOriginalUnavailableButCanStillCreateAnInstance() throws Exception {
        var f = ready();
        jdbc.update("UPDATE catalog_pack_revision SET source='legacy',blob_sha256=NULL WHERE pack_id=?",f.pack());
        assertThat(library.mine(f.user().id(),null).getFirst().originalAvailable()).isFalse();
        var response = send(f.user(),archivePath(f.pack()),"GET",null);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("error").path("code").asString()).isEqualTo("ORIGINAL_UNAVAILABLE");
        assertApi(() -> uploads.publishPrivate(f.user().id(),f.pack(),1,info()),"ORIGINAL_UNAVAILABLE");
        assertThat(create(f).id()).isNotBlank();
    }

    @Test
    void queueAndBeijingUploadStatisticsAreAdminOnlyAndReflectAttempts() throws Exception {
        var f = ready();
        assertThat(send(user(false),"/api/admin/import-jobs","GET",null).statusCode()).isEqualTo(403);
        var data = json.readTree(send(f.user(),"/api/admin/import-jobs","GET",null).body());
        assertThat(data.has("counts")).isTrue();
        jdbc.update("INSERT INTO content_upload_day(user_id,upload_day,uploads) VALUES(?,DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),777) ON DUPLICATE KEY UPDATE uploads=777",f.user().id());
        var overview = json.readTree(send(f.user(),"/api/admin/overview","GET",null).body());
        assertThat(overview.path("uploads").path("today").asLong()).isGreaterThanOrEqualTo(777);
        assertThat(overview.path("uploads").path("topUsers").get(0).path("userId").asString()).isEqualTo(f.user().id());
    }

    @Test
    void invalidSelectionRollsBackEntireImport() throws Exception {
        var f = ready();
        assertApi(() -> practice.create(f.user().id(),f.pack(),1,List.of(f.bank(),Ids.uuidV7()),null,true),"VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE user_id=?",Integer.class,f.user().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT instance_count FROM quota_private_revision WHERE pack_id=?",Long.class,f.pack())).isZero();
    }

    @Test
    void releasingVersionOneDoesNotReleaseTheRetainedVersionTenCharge() {
        var owner = user(false);
        var pack = Ids.uuidV7();
        tx.executeWithoutResult(status -> {
            quotas.retainPrivateResource(owner.id(),pack,1,3);
            quotas.retainPrivateResource(owner.id(),pack,10,10);
            quotas.retainPrivateInstance(owner.id(),pack,10);
            quotas.releasePrivateResource(owner.id(),pack);
        });
        assertThat(quotas.usage(owner.id()).storage().usedBytes()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT object_id FROM quota_storage_item WHERE user_id=?",String.class,owner.id())).isEqualTo(pack+":10");
    }

    @Test
    void failedQueueJobCanOnlyBeRetriedByAdminAndCancelledResourceCannotBeRetried() throws Exception {
        var f = ready();
        String id = jdbc.queryForObject("SELECT id FROM content_import_job WHERE pack_id=?",String.class,f.pack());
        jdbc.update("UPDATE content_import_job SET status='failed',failure_code='RULE_UNAVAILABLE' WHERE id=?",id);
        assertThat(send(user(false),"/api/admin/import-jobs/"+id+"/retry","POST",null).statusCode()).isEqualTo(403);
        var before = json.readTree(send(f.user(),"/api/admin/import-jobs","GET",null).body());
        assertThat(before.path("counts").path("failed").asLong()).isEqualTo(1);
        assertThat(send(f.user(),"/api/admin/import-jobs/"+id+"/retry","POST",null).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM content_import_job WHERE id=?",String.class,id)).isEqualTo("queued");
        uploads.delete(f.user().id(),f.pack());
        assertThat(send(f.user(),"/api/admin/import-jobs/"+id+"/retry","POST",null).statusCode()).isEqualTo(409);
    }

    @Test
    void uploadStatisticsFilterTheRequestedBeijingDayAndLimitTopUsersToFive() {
        var day = java.time.LocalDate.of(2000,1,2);
        var ids = new ArrayList<String>();
        for (int index=0;index<6;index++) {
            String id = user(false).id();
            ids.add(id);
            jdbc.update("INSERT INTO content_upload_day(user_id,upload_day,uploads) VALUES(?,?,?)",id,day,index+1);
            jdbc.update("INSERT INTO content_upload_day(user_id,upload_day,uploads) VALUES(?,?,999)",id,day.minusDays(1));
        }
        assertThat(stats.uploads(day)).isEqualTo(21);
        assertThat(stats.top(day)).extracting(row -> row.userId()).containsExactly(ids.get(5),ids.get(4),ids.get(3),ids.get(2),ids.get(1));
    }

    private Fixture ready() throws Exception {
        var owner = user(true);
        var bytes = archive();
        var accepted = receive(owner,bytes);
        assertThat(queue.runOne()).isTrue();
        assertThat(uploads.reviewState(owner.id(),accepted.packId(),1).status()).isEqualTo("approved");
        return new Fixture(owner,accepted.packId(),library.detail(owner.id(),accepted.packId()).revisions().getFirst().banks().getFirst().bankUuid(),bytes.length);
    }
    private LibraryPracticeService.Created create(Fixture f) {
        return practice.create(f.user().id(),f.pack(),1,List.of(f.bank()),null,true).getFirst();
    }
    private ImportUploadService.Accepted receive(User owner, byte[] bytes) {
        return uploads.receivePrivate(owner.id(),null,"测试.qbank",bytes.length,new ByteArrayInputStream(bytes));
    }
    private byte[] archive() throws Exception { return ImportArchives.zip(ImportArchives.files(List.of("单选题"),"group")); }
    private User user(boolean admin) {
        var result = auth.register(Ids.uuidV7()+"@example.test","资源验收","pass12345",null);
        if (admin) jdbc.update("UPDATE account_user SET is_admin=TRUE WHERE id=?",result.userId());
        return new User(result.userId(),"__Host-qf_session="+result.token());
    }
    private HttpResponse<String> send(User user,String path,String method,Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Cookie",user.cookie()).header("Origin",CatalogIntegrationTest.ORIGIN).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String archivePath(String pack) { return "/api/library/packs/"+pack+"/revisions/1/archive"; }
    private cloud.treenewbee.quizforge.catalog.application.PackInfo info() {
        return cloud.treenewbee.quizforge.catalog.application.PackInfo.of("公开版本","简介","详细说明","其他","","");
    }
    private void assertApi(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(code));
    }
}
