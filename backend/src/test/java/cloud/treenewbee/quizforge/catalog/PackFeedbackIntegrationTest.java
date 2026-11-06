package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.catalog.application.PackFeedbackService;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.FeedbackMapper;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.notification.application.NotificationService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 真实 HTTP、MySQL 和事务；合成广场元数据，不依赖导入和 rule-runner。 */
@Import(PackFeedbackIntegrationTest.TimeConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PackFeedbackIntegrationTest extends MySqlTestBase {
    @Autowired JdbcTemplate sql;
    @Autowired AuthService auth;
    @Autowired CatalogMapper catalog;
    @Autowired FeedbackMapper rows;
    @Autowired PackFeedbackService feedback;
    @Autowired MutableClock clock;
    @MockitoSpyBean AdminAuditService audit;
    @MockitoSpyBean NotificationService notifications;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    User owner;
    User admin;
    String pack;
    record User(String id, String cookie) {}

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-05T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary MutableClock feedbackClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        clock.now = Instant.parse("2026-11-05T12:00:00Z");
        owner = user();
        admin = user();
        sql.update("UPDATE account_user SET is_admin=TRUE WHERE id=?", admin.id());
        pack = pack();
    }

    User user() {
        var result = auth.register(Ids.uuidV7() + "@example.test", "反馈用户", "password123", null);
        return new User(result.userId(), "__Host-qf_session=" + result.token());
    }

    String pack() {
        String id = Ids.uuidV7();
        catalog.insertPack(id, owner.id(), clock.instant());
        catalog.insertRevision(new CatalogMapper.RevisionRow(id, 1, "反馈测试题库", "简介", "说明", "其他",
                "[]", "更新", "upload", "合成.qbank", new byte[32], 1, "[]", "pending",
                null, null, null, null, owner.id(), clock.instant()));
        sql.update("UPDATE catalog_pack SET visibility='public',approved_revision=1 WHERE id=?", id);
        sql.update("UPDATE catalog_pack_revision SET review_status='approved',validation_status='validated' WHERE pack_id=?", id);
        return id;
    }

    HttpResponse<String> request(User user, String path, String method, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:8088").header("Content-Type", "application/json");
        if (user != null) builder.header("Cookie", user.cookie());
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    String comments() { return "/api/plaza/packs/" + pack + "/comments"; }
    String reports() { return "/api/plaza/packs/" + pack + "/report"; }

    void error(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    <T> List<T> concurrent(int count, IntFunction<T> call) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(threads.submit(() -> { ready.countDown(); start.await(); return call.apply(index); }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    @Test void commentLengthTrimmingAndPlainTextArePreserved() throws Exception {
        String body = "<img src=x onerror=alert(1)>\n第二行";
        var result = request(owner, comments(), "POST", Map.of("body", "\uFEFF　" + body + "　\uFEFF"));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        assertThat(json.readTree(result.body()).path("comment").path("body").asString()).isEqualTo(body);
        assertThat(feedback.comment(owner.id(), pack, "字".repeat(2000)).body()).hasSize(2000);
        assertThat(feedback.comment(owner.id(), pack, "字").body()).isEqualTo("字");
        for (String invalid : List.of("", " \n　\uFEFF", "字".repeat(2001)))
            error(() -> feedback.comment(owner.id(), pack, invalid), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(request(owner, comments(), "POST", Map.of()).statusCode()).isEqualTo(400);
        assertThat(request(owner, comments(), "POST", Map.of("body", List.of("错"))).statusCode()).isEqualTo(400);
    }

    @Test void reportLengthTrimmingAndValidation() throws Exception {
        String id = feedback.report(owner.id(), pack, "　" + "字".repeat(1000) + "\uFEFF");
        assertThat(rows.report(id).orElseThrow().reason()).hasSize(1000);
        for (String invalid : List.of("", "　\n", "字".repeat(1001)))
            error(() -> feedback.report(owner.id(), pack, invalid), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(request(owner, reports(), "POST", Map.of()).statusCode()).isEqualTo(400);
        assertThat(request(owner, "/api/admin/catalog/reports/" + id + "/resolve", "POST",
                Map.of("decision", "removed", "note", "说明")).statusCode()).isEqualTo(403);
        error(() -> feedback.resolve(admin.id(), id, "invalid", "说明"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        error(() -> feedback.resolve(admin.id(), id, "handled", "　"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        error(() -> feedback.resolve(admin.id(), id, "handled", "字".repeat(1001)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test void concurrentCommentRateIsAtomicAndResetsByClock() throws Exception {
        var outcomes = concurrent(12, i -> {
            try { feedback.comment(owner.id(), pack, "并发" + i); return "ok"; }
            catch (ApiException e) { return e.code(); }
        });
        assertThat(Collections.frequency(outcomes, "ok")).isEqualTo(10);
        assertThat(Collections.frequency(outcomes, "FEEDBACK_RATE_LIMIT")).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM catalog_comment WHERE pack_id=?", Integer.class, pack)).isEqualTo(10);
        assertThat(feedback.comment(user().id(), pack, "另一用户")).isNotNull();
        clock.now = clock.now.plusSeconds(60);
        assertThat(feedback.comment(owner.id(), pack, "下一窗口")).isNotNull();
    }

    @Test void reportRateAllowsFivePerMinuteAndIsIndependentFromComments() {
        for (int i = 0; i < 5; i++) feedback.report(owner.id(), pack(), "举报");
        error(() -> feedback.report(owner.id(), pack, "第六次"), HttpStatus.TOO_MANY_REQUESTS, "FEEDBACK_RATE_LIMIT");
        assertThat(feedback.comment(owner.id(), pack, "评论有独立额度")).isNotNull();
        clock.now = clock.now.plusSeconds(60);
        assertThat(feedback.report(owner.id(), pack, "下一窗口")).isNotNull();
    }

    @Test void concurrentDuplicateReportKeepsOnePendingAndOriginalReason() throws Exception {
        var ids = concurrent(2, i -> feedback.report(owner.id(), pack, "原因" + i));
        assertThat(ids).containsOnly(ids.getFirst());
        String reason = rows.report(ids.getFirst()).orElseThrow().reason();
        assertThat(feedback.report(owner.id(), pack, "不能覆盖")).isEqualTo(ids.getFirst());
        assertThat(rows.report(ids.getFirst()).orElseThrow().reason()).isEqualTo(reason);
        assertThat(feedback.report(user().id(), pack, "另一用户")).isNotEqualTo(ids.getFirst());
        feedback.resolve(admin.id(), ids.getFirst(), "dismissed", "已核实");
        assertThat(feedback.report(owner.id(), pack, "补充证据")).isNotEqualTo(ids.getFirst());
    }

    @Test void hiddenPacksCannotReceiveCommentsOrReports() throws Exception {
        for (String mutation : List.of("visibility='private'", "status='deleted'", "approved_revision=NULL")) {
            String hidden = pack();
            sql.update("UPDATE catalog_pack SET " + mutation + " WHERE id=?", hidden);
            assertThat(request(owner, "/api/plaza/packs/" + hidden + "/comments", "POST", Map.of("body", "评论")).statusCode()).isEqualTo(404);
            assertThat(request(owner, "/api/plaza/packs/" + hidden + "/report", "POST", Map.of("reason", "举报")).statusCode()).isEqualTo(404);
            assertThat(request(null, "/api/plaza/packs/" + hidden + "/comments", "GET", null).statusCode()).isEqualTo(404);
        }
        sql.update("UPDATE catalog_pack_revision SET review_status='rejected' WHERE pack_id=?", pack);
        assertThat(request(owner, comments(), "POST", Map.of("body", "评论")).statusCode()).isEqualTo(404);
        assertThat(request(owner, reports(), "POST", Map.of("reason", "举报")).statusCode()).isEqualTo(404);
    }

    @Test void anonymousWritesAndBlockedUsersAreRejected() throws Exception {
        assertThat(request(null, comments(), "GET", null).statusCode()).isEqualTo(200);
        assertThat(request(null, comments(), "POST", Map.of("body", "评论")).statusCode()).isEqualTo(401);
        assertThat(request(null, reports(), "POST", Map.of("reason", "举报")).statusCode()).isEqualTo(401);
        sql.update("INSERT INTO account_block(user_id,actor_id,reason,created_at) VALUES(?,?,?,?)", owner.id(), admin.id(), "测试封禁", clock.instant());
        assertThat(request(owner, comments(), "POST", Map.of("body", "评论")).statusCode()).isEqualTo(401);
        assertThat(request(owner, reports(), "POST", Map.of("reason", "举报")).statusCode()).isEqualTo(401);
        error(() -> feedback.report(owner.id(), pack, "举报"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test void commentDeletionRequiresCommentAuthorOrAdministratorAndAuditsAdmin() throws Exception {
        User author = user(), stranger = user();
        String id = feedback.comment(author.id(), pack, "评论").id();
        String path = "/api/plaza/comments/" + id;
        assertThat(request(owner, path, "DELETE", null).statusCode()).isEqualTo(403);
        assertThat(request(stranger, path, "DELETE", null).statusCode()).isEqualTo(403);
        assertThat(request(author, "/api/admin/catalog/comments/" + id, "DELETE", null).statusCode()).isEqualTo(403);
        assertThat(rows.comment(id)).isPresent();
        assertThat(request(author, path, "DELETE", null).statusCode()).isEqualTo(204);
        id = feedback.comment(author.id(), pack, "管理员删除").id();
        assertThat(request(admin, "/api/admin/catalog/comments/" + id, "DELETE", null).statusCode()).isEqualTo(204);
        assertThat(rows.comment(id)).isEmpty();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=? AND action='comment-deleted'", Integer.class, id)).isEqualTo(1);
    }

    @Test void commentKeysetHandlesEqualTimestampsWithoutDuplicatesAndExposesOnlyOwnControls() {
        for (int i = 0; i < 23; i++) rows.insertComment(Ids.uuidV7(), pack, owner.id(), "评论" + i, clock.instant());
        var first = feedback.comments(owner.id(), pack, null);
        var next = feedback.comments(null, pack, first.nextCursor());
        assertThat(first.items()).hasSize(20).allMatch(PackFeedbackService.CommentView::canDelete);
        assertThat(next.items()).hasSize(3).noneMatch(PackFeedbackService.CommentView::canDelete);
        assertThat(first.items().stream().map(PackFeedbackService.CommentView::id)).doesNotContainAnyElementsOf(
                next.items().stream().map(PackFeedbackService.CommentView::id).toList());
        error(() -> feedback.comments(null, pack, "invalid"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test void adminReportKeysetOrdersPendingFirstWithoutDuplicates() {
        String isolated = user().id();
        var expected = new HashSet<String>();
        for (int i = 0; i < 23; i++) {
            String id = Ids.uuidV7();
            expected.add(id);
            rows.insertReport(id, pack(), isolated, "分页" + i, clock.instant());
            if (i % 2 == 0) rows.resolve(id, "handled", "处理说明", admin.id(), clock.instant().plusSeconds(1));
        }
        var first = feedback.reports(admin.id(), null);
        var all = new ArrayList<>(first.items());
        String cursor = first.nextCursor();
        while (cursor != null) {
            var next = feedback.reports(admin.id(), cursor);
            all.addAll(next.items());
            cursor = next.nextCursor();
        }
        assertThat(first.items()).hasSize(20);
        assertThat(all.stream().map(FeedbackMapper.Report::id).distinct()).hasSize(all.size());
        assertThat(all.stream().map(FeedbackMapper.Report::id)).containsAll(expected);
        assertThat(all.stream().map(FeedbackMapper.Report::isPending)).isSortedAccordingTo(Comparator.reverseOrder());
        error(() -> feedback.reports(admin.id(), "bad"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test void allResolutionsNotifyOnlyReporterAndAuditOnceOnReplay() throws Exception {
        for (String decision : List.of("removed", "dismissed", "handled")) {
            String target = pack();
            String id = feedback.report(owner.id(), target, "问题描述");
            var response = request(admin, "/api/admin/catalog/reports/" + id + "/resolve", "POST",
                    Map.of("decision", decision, "note", "处理说明"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(rows.report(id).orElseThrow().status()).isEqualTo(decision);
            feedback.resolve(admin.id(), id, decision, "处理说明");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=?", Integer.class, id)).isEqualTo(1);
            error(() -> feedback.resolve(admin.id(), id, decision, "不同说明"), HttpStatus.CONFLICT, "REPORT_ALREADY_HANDLED");
            assertThat(catalog.pack(target).orElseThrow().status()).isEqualTo(decision.equals("removed") ? "deleted" : "active");
        }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=? AND title='举报处理结果'", Integer.class, owner.id())).isEqualTo(3);
        assertThat(request(owner, "/api/notifications", "GET", null).body()).contains("举报处理结果");
        assertThat(sql.queryForList("SELECT body FROM notification WHERE target_id=? AND title='举报处理结果'", String.class, owner.id()))
                .hasSize(3).allSatisfy(body -> assertThat(body).contains("处理说明", "反馈测试题库"));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=? AND title='举报处理结果'", Integer.class, admin.id())).isZero();
    }

    @Test void concurrentDifferentResolutionsHaveOneWinnerAndOneNotice() throws Exception {
        String id = feedback.report(owner.id(), pack, "问题");
        var outcomes = concurrent(2, i -> {
            try { feedback.resolve(admin.id(), id, i == 0 ? "handled" : "dismissed", "说明"); return "ok"; }
            catch (ApiException e) { return e.code(); }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("ok", "REPORT_ALREADY_HANDLED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM notification WHERE target_id=? AND title='举报处理结果'", Integer.class, owner.id())).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void notificationFailureRollsBackReportPackDeletionAndAudit() {
        String id = feedback.report(owner.id(), pack, "问题");
        NotificationService target = AopTestUtils.getUltimateTargetObject(notifications);
        doThrow(new IllegalStateException("模拟通知失败")).when(target).publishSystem(eq(owner.id()), anyString(), anyString());
        assertThatThrownBy(() -> feedback.resolve(admin.id(), id, "removed", "说明")).isInstanceOf(IllegalStateException.class);
        assertThat(rows.report(id).orElseThrow().status()).isEqualTo("pending");
        assertThat(catalog.pack(pack).orElseThrow().status()).isEqualTo("active");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target=?", Integer.class, id)).isZero();
    }

    @Test void auditFailureRollsBackAdministratorCommentDeletion() {
        String id = feedback.comment(owner.id(), pack, "保留评论").id();
        AdminAuditService target = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("模拟审计失败")).when(target).record(eq(admin.id()), eq("catalog"), anyString(), eq(id), any());
        assertThatThrownBy(() -> feedback.deleteComment(admin.id(), id, true)).isInstanceOf(IllegalStateException.class);
        assertThat(rows.comment(id)).isPresent();
    }

    @Test void ordinaryUsersCannotReadOrResolveOtherUsersReports() throws Exception {
        String id = feedback.report(user().id(), pack, "私人举报原因");
        for (String path : List.of("/api/admin/catalog/reports", "/api/admin/catalog/reports/" + id)) {
            var response = request(owner, path, "GET", null);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("私人举报原因");
        }
        assertThat(request(owner, "/api/admin/catalog/reports/" + id + "/resolve", "POST",
                Map.of("decision", "removed", "note", "越权")).statusCode()).isEqualTo(403);
        assertThat(rows.report(id).orElseThrow().status()).isEqualTo("pending");
        assertThat(request(admin, "/api/admin/catalog/reports/" + id, "GET", null).body()).contains("私人举报原因");
    }

    @Test void pendingNewVersionDoesNotChangePublicCommentsOrDiscloseItsTitle() throws Exception {
        String id = feedback.report(owner.id(), pack, "报告当前版本");
        catalog.insertRevision(new CatalogMapper.RevisionRow(pack, 2, "机密待审标题", "机密简介", "机密说明", "其他",
                "[]", "机密更新", "upload", "合成.qbank", new byte[32], 1, "[]", "pending",
                null, null, null, null, owner.id(), clock.instant()));
        sql.update("UPDATE catalog_pack SET latest_revision=2 WHERE id=?", pack);
        assertThat(request(owner, comments(), "POST", Map.of("body", "仍可评论已审核版本")).statusCode()).isEqualTo(201);
        assertThat(request(null, comments(), "GET", null).body()).doesNotContain("机密");
        assertThat(feedback.reportDetail(admin.id(), id).title()).isEqualTo("反馈测试题库");
    }

    @Test void removalKeepsExistingPracticeInstancesAndCancelsPendingImportWork() throws Exception {
        String bank = Ids.uuidV7(), collection = Ids.uuidV7(), job = Ids.uuidV7();
        sql.update("INSERT INTO content_bank(id,content_sha256,source_title,format_version,features,question_count,created_at) "
                + "VALUES(?,UNHEX(SHA2(?,256)),'保留的题库',2,'{}',0,?)", bank, bank, clock.instant());
        sql.update("INSERT INTO practice_collection(id,user_id,title,bank_id,source,source_pack_id,created_at) "
                + "VALUES(?,?,'独立练习实例',?,'{}',?,?)", collection, owner.id(), bank, pack, clock.instant());
        sql.update("INSERT INTO content_import_job(id,pack_id,revision,status,available_at,created_at,updated_at) "
                + "VALUES(?,?,1,'queued',?,?,?)", job, pack, clock.instant(), clock.instant(), clock.instant());
        String report = feedback.report(owner.id(), pack, "问题");
        feedback.resolve(admin.id(), report, "removed", "下架资源，保留练习");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE id=? AND user_id=?", Integer.class, collection, owner.id())).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT status FROM content_import_job WHERE id=?", String.class, job)).isEqualTo("cancelled");
        assertThat(request(null, "/api/plaza/packs/" + pack, "GET", null).statusCode()).isEqualTo(404);
    }

    @Test void adminCanPageAndDeleteUnreportedCommentsEvenAfterPackRemoval() throws Exception {
        String id = feedback.comment(owner.id(), pack, "未被举报的评论").id();
        sql.update("UPDATE catalog_pack SET status='deleted' WHERE id=?", pack);
        assertThat(request(owner, "/api/admin/catalog/comments", "GET", null).statusCode()).isEqualTo(403);
        var page = feedback.adminComments(admin.id(), null);
        var ids = new ArrayList<>(page.items().stream().map(FeedbackMapper.Comment::id).toList());
        String cursor = page.nextCursor();
        while (cursor != null) {
            var next = feedback.adminComments(admin.id(), cursor);
            ids.addAll(next.items().stream().map(FeedbackMapper.Comment::id).toList());
            cursor = next.nextCursor();
        }
        assertThat(ids).contains(id).doesNotHaveDuplicates();
        assertThat(request(admin, "/api/admin/catalog/comments/" + id, "DELETE", null).statusCode()).isEqualTo(204);
        assertThat(rows.comment(id)).isEmpty();
    }

    @Test void invalidSubmissionAttemptsAlsoConsumeRateWindowWithoutWritingComments() {
        for (int i = 0; i < 10; i++)
            error(() -> feedback.comment(owner.id(), pack, "　"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        error(() -> feedback.comment(owner.id(), pack, "评论"), HttpStatus.TOO_MANY_REQUESTS, "FEEDBACK_RATE_LIMIT");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM catalog_comment WHERE pack_id=?", Integer.class, pack)).isZero();
    }
}
