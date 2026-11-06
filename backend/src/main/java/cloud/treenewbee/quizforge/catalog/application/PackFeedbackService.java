package cloud.treenewbee.quizforge.catalog.application;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.FeedbackMapper;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.notification.application.NotificationService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import cloud.treenewbee.quizforge.shared.web.KeysetCursor;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 评论、举报与处理；包行在举报行之前加锁，不对不存在的举报加锁。 */
@Service
public class PackFeedbackService {
    public record CommentView(String id, String userId, String author, String body,
                              java.time.Instant createdAt, boolean canDelete) {}
    public record Comments(List<CommentView> items, String nextCursor) {}
    public record Reports(List<FeedbackMapper.Report> items, String nextCursor) {}
    public record AdminComments(List<FeedbackMapper.Comment> items, String nextCursor) {}
    private final FeedbackMapper rows;
    private final CatalogMapper catalog;
    private final PlazaService plaza;
    private final AccountAdminService admins;
    private final AccountMapper accounts;
    private final UserMapper users;
    private final ImportUploadService uploads;
    private final AdminAuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final TransactionTemplate rateTx;
    private final Clock clock;

    public PackFeedbackService(FeedbackMapper rows, CatalogMapper catalog, PlazaService plaza,
            AccountAdminService admins, AccountMapper accounts, UserMapper users, ImportUploadService uploads,
            AdminAuditService audit, NotificationService notifications, TransactionTemplate tx, Clock clock) {
        this.rows = rows;
        this.catalog = catalog;
        this.plaza = plaza;
        this.admins = admins;
        this.accounts = accounts;
        this.users = users;
        this.uploads = uploads;
        this.audit = audit;
        this.notifications = notifications;
        this.tx = tx;
        this.rateTx = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        this.rateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public Comments comments(String user, String pack, String cursor) {
        plaza.visible(pack);
        var after = KeysetCursor.decode(cursor);
        boolean admin = user != null && admins.isAdmin(user);
        var found = rows.comments(pack, after == null ? null : after.time(), after == null ? null : after.id());
        var items = found.stream().limit(20).map(row -> new CommentView(row.id(), row.userId(), row.author(),
                row.body(), row.createdAt(), admin || row.userId().equals(user))).toList();
        return new Comments(items, found.size() > 20
                ? KeysetCursor.encode(items.getLast().createdAt(), items.getLast().id()) : null);
    }

    public FeedbackMapper.Comment comment(String user, String pack, String input) {
        active(user);
        rate(user, "comment", 10);
        String body = text(input, 2000, "评论");
        return tx.execute(status -> {
            writable(pack);
            String id = Ids.uuidV7();
            changed(rows.insertComment(id, pack, user, body, clock.instant()));
            return rows.comment(id).orElseThrow();
        });
    }

    /** 后台可管理未被举报或已下架包中的评论，不依赖公开详情可见性。 */
    public AdminComments adminComments(String user, String cursor) {
        admins.requireAdmin(user);
        var after = KeysetCursor.decode(cursor);
        var found = rows.adminComments(after == null ? null : after.time(), after == null ? null : after.id());
        var items = List.copyOf(found.subList(0, Math.min(20, found.size())));
        return new AdminComments(items, found.size() > 20
                ? KeysetCursor.encode(items.getLast().createdAt(), items.getLast().id()) : null);
    }

    public String report(String user, String pack, String input) {
        active(user);
        rate(user, "report", 5);
        String reason = text(input, 1000, "举报原因");
        return tx.execute(status -> {
            writable(pack);
            // 唯一键约束只覆盖 pending；重复举报保留最初的原因，不覆盖他人的举报。
            rows.insertReport(Ids.uuidV7(), pack, user, reason, clock.instant());
            return rows.pending(pack, user).orElseThrow();
        });
    }

    public void deleteComment(String user, String id, boolean adminRoute) {
        tx.executeWithoutResult(status -> {
            active(user);
            boolean admin = admins.isAdmin(user);
            if (adminRoute) admins.requireAdmin(user);
            var comment = rows.comment(id).orElseThrow(PackFeedbackService::commentMissing);
            if (!admin && !comment.userId().equals(user))
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅评论作者或管理员可以删除评论");
            int count = rows.deleteComment(id, user, admin);
            if (count == 0) throw commentMissing();
            if (admin) audit.record(user, "catalog", "comment-deleted", id, Map.of("packId", comment.packId()));
        });
    }

    public Reports reports(String user, String cursor) {
        admins.requireAdmin(user);
        Integer pending = null;
        KeysetCursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.length() > 200 || !cursor.matches("[01]\\..+")) throw invalid("分页参数无效");
            pending = cursor.charAt(0) - '0';
            after = KeysetCursor.decode(cursor.substring(2));
        }
        var found = rows.reports(pending, after == null ? null : after.time(), after == null ? null : after.id());
        var items = List.copyOf(found.subList(0, Math.min(20, found.size())));
        String next = found.size() > 20 ? items.getLast().isPending() + "."
                + KeysetCursor.encode(items.getLast().createdAt(), items.getLast().id()) : null;
        return new Reports(items, next);
    }

    public FeedbackMapper.Report reportDetail(String user, String id) {
        admins.requireAdmin(user);
        return rows.report(id).orElseThrow(PackFeedbackService::reportMissing);
    }

    public FeedbackMapper.Report resolve(String user, String id, String decision, String input) {
        admins.requireAdmin(user);
        if (!List.of("removed", "dismissed", "handled").contains(Objects.toString(decision, "")))
            throw invalid("请选择下架题库包、驳回或已处理");
        String note = text(input, 1000, "处理说明");
        // 元数据在事务外定位；事务内先锁已存在的包，再锁已存在的举报。
        var initial = rows.report(id).orElseThrow(PackFeedbackService::reportMissing);
        return tx.execute(status -> {
            var pack = catalog.lock(initial.packId()).orElseThrow(PackFeedbackService::reportMissing);
            rows.lockReport(id).orElseThrow(PackFeedbackService::reportMissing);
            var current = rows.report(id).orElseThrow(PackFeedbackService::reportMissing);
            if (!current.status().equals("pending")) {
                if (current.status().equals(decision) && Objects.equals(current.resolution(), note)) return current;
                throw new ApiException(HttpStatus.CONFLICT, "REPORT_ALREADY_HANDLED", "这条举报已处理，请刷新列表");
            }
            if (decision.equals("removed") && pack.status().equals("active")) uploads.delete(pack.ownerId(), pack.id());
            changed(rows.resolve(id, decision, note, user, clock.instant()));
            audit.record(user, "catalog", "report-" + decision, id, Map.of("packId", pack.id(), "note", note));
            String outcome = switch (decision) {
                case "removed" -> "题库包已下架";
                case "dismissed" -> "举报已驳回";
                default -> "举报已处理";
            };
            notifications.publishSystem(current.userId(), "举报处理结果",
                    "你对「" + Objects.toString(current.title(), "题库包") + "」的举报：" + outcome + "\n处理说明：" + note);
            return rows.report(id).orElseThrow();
        });
    }

    private void writable(String pack) {
        plaza.visible(pack);
        var locked = catalog.lock(pack).orElseThrow(PackFeedbackService::reportMissing);
        if (!locked.status().equals("active") || locked.approvedRevision() == null) throw reportMissing();
    }

    private void active(String user) {
        if (users.findById(user).isEmpty() || accounts.isBlocked(user))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    }

    private void rate(String user, String kind, int max) {
        // 沿用旧 Hub 的尝试次数口径：校验或业务失败也计次，不与业务回滚一起撤销。
        boolean exceeded = Boolean.TRUE.equals(rateTx.execute(status -> {
            var now = clock.instant();
            rows.hit(user, kind, now, now.plusSeconds(60));
            return rows.attempts(user, kind) > max;
        }));
        if (exceeded)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FEEDBACK_RATE_LIMIT", "操作过于频繁，请稍后再试");
    }

    static String text(String input, int max, String label) {
        String value = input == null ? "" : input.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (value.isEmpty() || value.length() > max) throw invalid(label + "须为 1～" + max + " 个字符");
        return value;
    }

    private static void changed(int count) { if (count != 1) throw new IllegalStateException("反馈写入未生效"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message); }
    private static ApiException commentMissing() { return new ApiException(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "评论不存在"); }
    private static ApiException reportMissing() { return new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "举报或题库包不存在"); }
}
