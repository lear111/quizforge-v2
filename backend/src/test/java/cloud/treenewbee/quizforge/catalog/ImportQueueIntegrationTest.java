package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.application.ImportQueueJobs;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.content.domain.ImportArchives;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.json.JsonMapper;

/** 真实 MySQL 与真实 runner；门控由任务领取和可控时钟推进，接收路径不推进工作线程。 */
@Import(ImportQueueIntegrationTest.TimeConfig.class)
@Timeout(180)
class ImportQueueIntegrationTest extends MySqlTestBase {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { CatalogIntegrationTest.properties(registry); }

    @Autowired ImportUploadService uploads;
    @Autowired ImportQueueJobs queue;
    @Autowired ImportQueueMapper jobs;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuotaService quotas;
    @Autowired MutableClock clock;
    @Autowired cloud.treenewbee.quizforge.catalog.application.CatalogPublishService publishing;
    @MockitoSpyBean RuleRunnerClient runner;
    @MockitoSpyBean cloud.treenewbee.quizforge.notification.application.NotificationService notifications;
    @Value("${local.server.port}") int port;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    record User(String id, String cookie) {}

    @BeforeEach
    void resetData() {
        reset(runner, notifications);
        clock.now = Instant.parse("2026-11-03T04:00:00Z");
        jdbc.update("UPDATE content_import_slot SET job_id=NULL,lease_until=NULL");
        for (String table : List.of("content_import_job", "content_validation_cache", "catalog_pack_event", "catalog_pack_file",
                "catalog_pack_bank", "catalog_pack_revision", "catalog_pack", "practice_collection", "content_question",
                "content_bank_asset", "content_bank", "content_extension_file", "content_extension_package", "admin_extension_review")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void rawUploadReturnsPendingWithoutRulesAndReservesOriginalBytes() throws Exception {
        User user = user(false);
        byte[] bytes = archive();
        var response = raw(user, bytes, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        String pack = json.readTree(response.body()).path("packId").asString();
        assertThat(state(pack)).isEqualTo("queued");
        assertThat(count("content_question")).isZero();
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(bytes.length);
        verifyNoInteractions(runner);
    }

    @Test
    void queueIsFifoAndGlobalSlotPreventsSecondClaimAcrossConcurrentTransactions() throws Exception {
        User user = user(false);
        var first = receive(user, archive());
        receive(user, archive());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var a = executor.submit(() -> { gate.await(); return queue.claim(); });
            var b = executor.submit(() -> { gate.await(); return queue.claim(); });
            gate.countDown();
            var claims = new java.util.ArrayList<ImportQueueMapper.Job>();
            claims.add(a.get(10, TimeUnit.SECONDS));
            claims.add(b.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Objects::nonNull).toList()).singleElement()
                    .satisfies(job -> assertThat(job.packId()).isEqualTo(first.packId()));
        }
    }

    @Test
    void infrastructureFailureRetriesThreeTimesWithIncreasingBackoffThenRemainsPendingForUser() throws Exception {
        var accepted = receive(user(false), archive());
        doThrow(new RuleRunnerException("不可用")).when(runner).execute(anyString(), any(), anyMap());
        long[] delays = {5, 20, 45};
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(queue.runOne()).isTrue();
            assertThat(queue.runOne()).isFalse();
            if (attempt < 3) clock.now = clock.now.plusSeconds(delays[attempt]);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM content_import_job", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM content_import_job", String.class)).isEqualTo("failed");
        assertThat(state(accepted.packId())).isEqualTo("processing_failed");
        assertThat(review(accepted.packId())).isEqualTo("pending");
        assertThat(count("content_question")).isZero();
    }

    @Test
    void invalidPackageIsRejectedWithNotificationAndReuploadKeepsOldResult() throws Exception {
        User user = user(false);
        var first = receive(user, new byte[] {1, 2, 3});
        assertThat(queue.runOne()).isTrue();
        assertThat(review(first.packId())).isEqualTo("rejected");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=?", Integer.class, user.id())).isPositive();
        byte[] valid = archive();
        var second = uploads.receivePrivate(user.id(), first.packId(), "重传.qbank", valid.length, new ByteArrayInputStream(valid));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(queue.runOne()).isTrue();
        assertThat(jdbc.queryForList("SELECT review_status FROM catalog_pack_revision ORDER BY revision", String.class))
                .containsExactly("rejected", "pending");
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(valid.length + 3);
    }

    @Test
    void adminUploadApprovesPendingExtensionsAndVersionWithAudit() throws Exception {
        User admin = user(true);
        var accepted = receive(admin, archive());
        assertThat(queue.runOne()).isTrue();
        assertThat(review(accepted.packId())).isEqualTo("approved");
        assertThat(jdbc.queryForList("SELECT status FROM admin_extension_review", String.class)).containsOnly("approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE actor_id=? AND action='extension-auto-approved'",
                Integer.class, admin.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT approved_revision FROM catalog_pack WHERE id=?", Integer.class, accepted.packId())).isEqualTo(1);
    }

    @Test
    void revokedAndRejectedExtensionsAreNeverRestoredByAdminUpload() throws Exception {
        receive(user(false), archive());
        queue.runOne();
        for (String status : List.of("revoked", "rejected")) {
            jdbc.update("UPDATE admin_extension_review SET status=?", status);
            var accepted = receive(user(true), archive());
            queue.runOne();
            assertThat(jdbc.queryForObject("SELECT status FROM admin_extension_review", String.class)).isEqualTo(status);
            assertThat(review(accepted.packId())).isEqualTo("pending");
        }
    }

    @Test
    void blockedAdminCannotAutoApproveAfterUpload() throws Exception {
        User admin = user(true);
        var accepted = receive(admin, archive());
        jdbc.update("INSERT INTO account_block(user_id,actor_id,reason,created_at) VALUES(?,?,'封禁',?)", admin.id(), admin.id(), clock.instant());
        queue.runOne();
        assertThat(review(accepted.packId())).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT status FROM admin_extension_review", String.class)).isEqualTo("pending");
    }

    @Test
    void cacheHitSkipsAllRulesAndLaterApprovalNotifiesWithoutRevalidating() throws Exception {
        User user = user(false);
        var first = receive(user, archive());
        queue.runOne();
        clearInvocations(runner);
        var second = receive(user, archive());
        queue.runOne();
        verify(runner, never()).execute(anyString(), any(), anyMap());
        jdbc.update("UPDATE admin_extension_review SET status='approved'");
        queue.reconcilePrivate();
        assertThat(review(first.packId())).isEqualTo("approved");
        assertThat(review(second.packId())).isEqualTo("approved");
        verify(runner, never()).execute(anyString(), any(), anyMap());
    }

    @Test
    void cancelledRunningJobCannotStoreBusinessDataAndReleasesQuota() throws Exception {
        User user = user(false);
        var accepted = receive(user, archive());
        var job = queue.claim();
        uploads.delete(user.id(), accepted.packId());
        queue.process(job);
        assertThat(count("content_question")).isZero();
        assertThat(count("catalog_pack_bank")).isZero();
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("cancelled");
    }

    @Test
    void expiredLeaseIsReclaimedAndOldGenerationCannotCommitOrReleaseNewSlot() throws Exception {
        var accepted = receive(user(false), archive());
        var old = queue.claim();
        clock.now = clock.now.plusSeconds(121);
        assertThat(queue.claim()).isNull();
        clock.now = clock.now.plusSeconds(5);
        var fresh = queue.claim();
        assertThat(fresh.generation()).isGreaterThan(old.generation());
        queue.process(old);
        assertThat(count("content_question")).isZero();
        assertThat(queue.claim()).isNull();
        queue.process(fresh);
        assertThat(state(accepted.packId())).isEqualTo("validated");
    }

    @Test
    void interruptedInputLeavesNoEntryNoQuotaAndNoTemporaryFile() throws Exception {
        User user = user(false);
        try (var before = java.nio.file.Files.list(CatalogIntegrationTest.UPLOADS)) {
            long files = before.count();
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("中断"); }
            };
            assertThatThrownBy(() -> uploads.receivePrivate(user.id(), null, "中断.qbank", 100, broken))
                    .isInstanceOf(java.io.UncheckedIOException.class);
            assertThat(count("catalog_pack")).isZero();
            assertThat(count("content_import_job")).isZero();
            assertThat(quotas.usage(user.id()).storage().usedBytes()).isZero();
            try (var after = java.nio.file.Files.list(CatalogIntegrationTest.UPLOADS)) {
                assertThat(after.count()).isEqualTo(files);
            }
        }
    }

    @Test
    void dailyLimitResetsAtBeijingMidnight() {
        User user = user(false);
        clock.now = Instant.parse("2026-11-03T15:59:59Z");
        for (int i = 0; i < 20; i++) uploads.preflight(user.id(), 0, true);
        assertThatThrownBy(() -> uploads.preflight(user.id(), 0, true))
                .isInstanceOf(cloud.treenewbee.quizforge.shared.web.ApiException.class).hasMessage("请稍后再试");
        clock.now = clock.now.plusSeconds(1);
        assertThatCode(() -> uploads.preflight(user.id(), 0, true)).doesNotThrowAnyException();
    }

    @Test
    void concurrentReservationsCannotExceedQuotaAndFailedTransactionHasNoEntry() throws Exception {
        User user = user(false);
        byte[] bytes = archive();
        quotas.usage(user.id());
        jdbc.update("UPDATE quota_account SET storage_limit=? WHERE user_id=?", bytes.length, user.id());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> upload = () -> {
                gate.await();
                try { receive(user, bytes); return true; }
                catch (cloud.treenewbee.quizforge.shared.web.ApiException rejected) { return false; }
            };
            var a = executor.submit(upload);
            var b = executor.submit(upload);
            gate.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("catalog_pack")).isEqualTo(1);
        assertThat(count("content_import_job")).isEqualTo(1);
        assertThat(quotas.usage(user.id()).storage().usedBytes()).isEqualTo(bytes.length);
    }

    @Test
    void entryOperationsRejectOtherUsers() throws Exception {
        var accepted = receive(user(false), archive());
        User stranger = user(false);
        var response = http.send(HttpRequest.newBuilder(uri("/api/library/uploads/" + accepted.packId() + "/revisions/1"))
                .header("Cookie", stranger.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> uploads.delete(stranger.id(), accepted.packId()))
                .isInstanceOf(cloud.treenewbee.quizforge.shared.web.ApiException.class);
        byte[] content = archive();
        assertThatThrownBy(() -> uploads.receivePrivate(stranger.id(), accepted.packId(), "越权.qbank",
                content.length, new ByteArrayInputStream(content)))
                .isInstanceOf(cloud.treenewbee.quizforge.shared.web.ApiException.class)
                .extracting(error -> ((cloud.treenewbee.quizforge.shared.web.ApiException) error).code())
                .isEqualTo("PACK_NOT_FOUND");
    }

    @Test
    void foregroundPressureYieldsBackgroundWithoutConsumingRetryBudget() throws Exception {
        receive(user(false), archive());
        doThrow(new RuleRunnerClient.BackgroundBusy()).when(runner).execute(anyString(), any(), anyMap());
        assertThat(queue.runOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM content_import_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM content_import_job", String.class)).isEqualTo("queued");
        assertThat(queue.claim()).isNull();
    }

    @Test
    void rulesAreOutsideTransactionAndOtherClaimCannotExceedConcurrency() throws Exception {
        User owner = user(false);
        var first = receive(owner, archive());
        receive(user(false), archive());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(runner).execute(eq("capabilities"), any(), anyMap());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var processing = executor.submit(queue::runOne);
            try {
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(queue.claim()).isNull();
                uploads.delete(owner.id(), first.packId());
            } finally { release.countDown(); }
            assertThat(processing.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(count("content_question")).isZero();
            verify(runner, never()).execute(eq("validateBank"), any(), anyMap());
        }
    }

    @Test
    void crossBankQuestionsAreGroupedIntoOneValidateAndOneScoreBatchPerExtension() throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "group");
        String from = ImportArchives.bank("单选题");
        String to = ImportArchives.bank("第二本");
        new java.util.LinkedHashMap<>(files).forEach((path, bytes) -> {
            if (path.startsWith(from + "/")) files.put(path.replace(from + "/", to + "/"), bytes);
        });
        ImportArchives.edit(files, to + "/bank.json", bank -> bank.put("id", Ids.uuidV7()));
        receive(user(false), ImportArchives.zip(files));
        queue.runOne();
        verify(runner, times(1)).execute(eq("validateBank"), any(), anyMap());
        verify(runner, times(1)).execute(eq("scoreBatch"), any(), anyMap());
        assertThat(count("catalog_pack_bank")).isEqualTo(2);
    }

    @Test
    void oldFormatExplainsHowToUpgradeAndBadQuestionIdentifiesBankAndQuestion() throws Exception {
        var files = ImportArchives.files(List.of("单选题"), "group");
        ImportArchives.edit(files, "quizforge-package.json", marker -> marker.put("formatVersion", 3));
        var old = receive(user(false), ImportArchives.zip(files));
        queue.runOne();
        assertThat(uploads.reviewState(jdbc.queryForObject("SELECT owner_id FROM catalog_pack WHERE id=?", String.class, old.packId()), old.packId(), 1).reason())
                .contains("旧格式", "Windows", "转换工具");
        files = ImportArchives.files(List.of("单选题"), "group");
        ImportArchives.edit(files, ImportArchives.bank("单选题") + "/bank.json", bank -> {
            var question = (Map<String,Object>) ((List<?>)bank.get("questions")).getFirst();
            question.put("data", Map.of("unknown", true));
        });
        var bad = receive(user(false), ImportArchives.zip(files));
        queue.runOne();
        String reason = jdbc.queryForObject("SELECT validation_reason FROM catalog_pack_revision WHERE pack_id=?", String.class, bad.packId());
        assertThat(reason).contains("单选题", "题目");
    }

    @Test
    void oversizedClaimIsRejectedBeforeReadingAndActualStreamIsAlsoBounded() {
        User user = user(false);
        InputStream forbidden = new InputStream() {
            @Override public int read() { throw new AssertionError("不应读取正文"); }
        };
        assertThatThrownBy(() -> uploads.receivePrivate(user.id(), null, "大包.qbank", 128L*1024*1024+1, forbidden))
                .isInstanceOf(cloud.treenewbee.quizforge.shared.web.ApiException.class);
        assertThat(count("catalog_pack")).isZero();
    }

    @Test
    void publicAdminUploadApprovesVersionButOrdinaryPublicUploadStillRequiresReview() throws Exception {
        byte[] data = archive();
        var path = java.nio.file.Files.createTempFile("qf-public-queue-test-", ".qbank");
        try {
            java.nio.file.Files.write(path, data);
            User admin = user(true);
            var first = publishing.publishUpload(admin.id(), new cloud.treenewbee.quizforge.catalog.application.PackInfo(
                    "公开验收", "简介", "说明", "其他", List.of(), ""), true, path, "公开.qbank", List.of());
            assertThat(review(first.packId())).isEqualTo("pending");
            verifyNoInteractions(runner);
            queue.runOne();
            assertThat(review(first.packId())).isEqualTo("approved");
            assertThat(quotas.usage(admin.id()).storage().usedBytes()).isZero();
            User ordinary = user(false);
            var second = publishing.publishUpload(ordinary.id(), new cloud.treenewbee.quizforge.catalog.application.PackInfo(
                    "普通公开验收", "简介", "说明", "其他", List.of(), ""), true, path, "公开.qbank", List.of());
            queue.runOne();
            assertThat(review(second.packId())).isEqualTo("pending");
            assertThat(state(second.packId())).isEqualTo("validated");
            assertThat(quotas.usage(ordinary.id()).storage().usedBytes()).isZero();
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @Test
    void extensionRejectionNotifiesOnceAndPrivateEntryCanBeResubmitted() throws Exception {
        User user = user(false);
        var first = receive(user, archive());
        queue.runOne();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=?", Integer.class, user.id());
        jdbc.update("UPDATE admin_extension_review SET status='rejected',reason='测试原因'");
        queue.reconcilePrivate();
        queue.reconcilePrivate();
        queue.reconcilePrivate();
        assertThat(uploads.reviewState(user.id(), first.packId(), 1).status()).isEqualTo("rejected");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=?", Integer.class, user.id())).isEqualTo(before + 1);
        byte[] data = archive();
        var second = uploads.receivePrivate(user.id(), first.packId(), "重试.qbank", data.length, new ByteArrayInputStream(data));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_pack_event WHERE pack_id=? AND action='extension-rejected'",
                Integer.class, first.packId())).isEqualTo(1);
    }

    @Test
    void leaseExpiryBeforeCommitRollsBackContentApprovalAuditAndNotificationTogether() throws Exception {
        User admin = user(true);
        receive(admin, archive());
        doAnswer(call -> {
            clock.now = clock.now.plusSeconds(121);
            return call.callRealMethod();
        }).when(notifications).publishSystem(eq(admin.id()), anyString(), anyString());
        assertThat(queue.runOne()).isTrue();
        assertThat(count("content_question")).isZero();
        assertThat(count("catalog_pack_bank")).isZero();
        assertThat(count("admin_extension_review")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=?", Integer.class, admin.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE actor_id=? AND action='extension-auto-approved'",
                Integer.class, admin.id())).isZero();
    }

    private ImportUploadService.Accepted receive(User user, byte[] bytes) {
        return uploads.receivePrivate(user.id(), null, "测试.qbank", bytes.length, new ByteArrayInputStream(bytes));
    }
    private HttpResponse<String> raw(User user, byte[] bytes, String pack) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/library/uploads?filename=test.qbank" + (pack == null ? "" : "&packId=" + pack)))
                .header("Origin", CatalogIntegrationTest.ORIGIN).header("Cookie", user.cookie())
                .header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private byte[] archive() throws Exception { return ImportArchives.zip(ImportArchives.files(List.of("单选题"), "group")); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String state(String pack) { return jdbc.queryForObject("SELECT validation_status FROM catalog_pack_revision WHERE pack_id=? ORDER BY revision DESC LIMIT 1", String.class, pack); }
    private String review(String pack) { return jdbc.queryForObject("SELECT review_status FROM catalog_pack_revision WHERE pack_id=? ORDER BY revision DESC LIMIT 1", String.class, pack); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private User user(boolean admin) {
        var created = auth.register(Ids.uuidV7() + "@example.test", "队列验收", "pass12345", null);
        if (admin) jdbc.update("UPDATE account_user SET is_admin=TRUE WHERE id=?", created.userId());
        return new User(created.userId(), "__Host-qf_session=" + created.token());
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock queueClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-03T04:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
