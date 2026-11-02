package cloud.treenewbee.quizforge.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.notification.infrastructure.NotificationMapper;
import cloud.treenewbee.quizforge.notification.infrastructure.NotificationMapper.NotificationRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import cloud.treenewbee.quizforge.shared.web.KeysetCursor;

/**
 * 站内通知中心（功能盘点 I 节）。
 *
 * <ul>
 *   <li>管理员：新建、查看、删除、置顶；不能修改（要改就删除重发）。全部记入审计，记录发送的管理员。</li>
 *   <li>用户：铃铛红点 = 存在比“上次打开通知界面的时间”更新的可见通知；打开界面后红点消失；
 *       单条“已查看”用用户 × 通知的已读记录；置顶排最前。</li>
 *   <li>发给所有人的通知只存一份；题库通知在读取时按用户是否拥有该题库筛选。</li>
 * </ul>
 */
@Service
public class NotificationService {

    public static final int TITLE_MAX = 100;
    public static final int BODY_MAX = 5000;
    static final int USER_LIST_LIMIT = 100;
    static final int MAX_BANKS = 500;
    private static final Pattern TRAILING = Pattern.compile("[\\s\\p{Z}\\uFEFF]+$");
    private static final Pattern LEADING = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+");

    public enum Audience { ALL, USER, BANK }

    public record AdminItem(String id, String audience, String targetId, String source, String title, String body,
                            boolean pinned, Sender createdBy, Instant createdAt) {
    }

    public record Sender(String id, String name) {
    }

    public record AdminPage(List<AdminItem> items, String nextCursor) {
    }

    public record UserItem(String id, String source, String title, boolean pinned, Instant createdAt, boolean read) {
    }

    public record UserList(List<UserItem> items, boolean unseen) {
    }

    public record Detail(String id, String source, String title, String body, boolean pinned, Instant createdAt) {
    }

    private final NotificationMapper mapper;
    private final BankOwnership banks;
    private final UserMapper users;
    private final AdminAuditService audit;
    private final Clock clock;

    public NotificationService(NotificationMapper mapper, BankOwnership banks, UserMapper users,
                               AdminAuditService audit, Clock clock) {
        this.mapper = mapper;
        this.banks = banks;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 管理员

    /** 管理员发布：发给所有人或单个用户。 */
    @Transactional
    public String publishByAdmin(String actorId, Audience audience, String userId, String title, String body,
                                 boolean pinned) {
        String target = switch (audience) {
            case ALL -> "";
            case USER -> {
                if (userId == null || users.findById(userId).isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND", "接收通知的用户不存在");
                }
                yield userId;
            }
            case BANK -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        };
        String id = insert(audience, target, "admin", title, body, pinned, actorId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("title", clean(title, TITLE_MAX));
        detail.put("audience", audience.name().toLowerCase());
        detail.put("targetId", target);
        detail.put("pinned", pinned);
        audit.record(actorId, "notification", "notification-created", id, detail);
        return id;
    }

    /**
     * 发给拥有某题库的用户（题库广场发布新版本时由发布者填写升级说明）。读取时按用户是否拥有该题库筛选，
     * 不为每个用户复制。publisherId 为发布者，可为空。
     */
    @Transactional
    public String publishToBankOwners(String bankKey, String title, String body, String publisherId) {
        if (bankKey == null || bankKey.isBlank() || bankKey.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        }
        return insert(Audience.BANK, bankKey, "publisher", title, body, false, publisherId);
    }

    /** 系统通知（例如审核结果），发给单个用户。 */
    @Transactional
    public String publishSystem(String userId, String title, String body) {
        return insert(Audience.USER, userId, "system", title, body, false, null);
    }

    @Transactional
    public void delete(String actorId, String id) {
        NotificationRow row = require(id);
        mapper.deleteReads(id);
        if (mapper.delete(id) != 1) throw new IllegalStateException("Notification vanished while deleting");
        audit.record(actorId, "notification", "notification-deleted", id,
                Map.of("title", row.title(), "audience", row.audience(), "targetId", row.targetId()));
    }

    @Transactional
    public void pin(String actorId, String id, boolean pinned) {
        NotificationRow row = require(id);
        if (row.pinned() == pinned) return;
        mapper.setPinned(id, pinned);
        audit.record(actorId, "notification", pinned ? "notification-pinned" : "notification-unpinned", id,
                Map.of("title", row.title()));
    }

    public AdminPage adminPage(String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        KeysetCursor after = KeysetCursor.decode(cursor);
        var rows = mapper.adminPage(after == null ? null : after.time(), after == null ? null : after.id(), size + 1);
        var items = rows.stream().limit(size).map(row -> new AdminItem(row.id(), row.audience(), row.targetId(),
                row.source(), row.title(), row.body(), row.pinned(),
                row.createdBy() == null ? null : new Sender(row.createdBy(), row.createdByName()), row.createdAt()))
                .toList();
        String next = rows.size() > size ? KeysetCursor.encode(items.getLast().createdAt(), items.getLast().id()) : null;
        return new AdminPage(items, next);
    }

    // ---------------------------------------------------------------- 用户

    /** 铃铛红点：是否有比上次打开通知界面更新的可见通知。每次打开页面都会调用，只做索引上的“是否存在”判断。 */
    public boolean hasUnseen(String userId) {
        Instant since = mapper.seenAt(userId).orElse(Instant.EPOCH);
        return mapper.hasNewer(userId, banks.ownedBankKeys(userId, MAX_BANKS), since);
    }

    /** 通知列表（不改变任何状态；打开界面时另调 {@link #markSeen}）。 */
    public UserList list(String userId) {
        List<String> keys = banks.ownedBankKeys(userId, MAX_BANKS);
        var items = mapper.userList(userId, keys, USER_LIST_LIMIT).stream()
                .map(row -> new UserItem(row.id(), row.source(), row.title(), row.pinned(), row.createdAt(), row.read()))
                .toList();
        Instant since = mapper.seenAt(userId).orElse(Instant.EPOCH);
        return new UserList(items, mapper.hasNewer(userId, keys, since));
    }

    public Detail detail(String userId, String id) {
        NotificationRow row = mapper.findVisible(id, userId, banks.ownedBankKeys(userId, MAX_BANKS))
                .orElseThrow(NotificationService::notFound);
        return new Detail(row.id(), row.source(), row.title(), row.body(), row.pinned(), row.createdAt());
    }

    /** 打开通知界面：记录打开时间，铃铛红点随之消失。 */
    public void markSeen(String userId) {
        mapper.markSeen(userId, clock.instant());
    }

    /** 点开一条通知：记录已查看；不可见的通知返回 404。 */
    public void markRead(String userId, String id) {
        List<String> keys = banks.ownedBankKeys(userId, MAX_BANKS);
        if (mapper.findVisible(id, userId, keys).isEmpty()) throw notFound();
        mapper.markRead(id, userId, keys, clock.instant());
    }

    // ---------------------------------------------------------------- 内部

    private String insert(Audience audience, String target, String source, String title, String body, boolean pinned,
                          String createdBy) {
        String id = Ids.uuidV7();
        mapper.insert(id, audience.name().toLowerCase(), target, source, clean(title, TITLE_MAX), body(body), pinned,
                createdBy, clock.instant());
        return id;
    }

    private NotificationRow require(String id) {
        return mapper.find(id).orElseThrow(NotificationService::notFound);
    }

    /** 标题：去首尾空白，不允许换行，1–100 个字符。 */
    static String clean(String title, int max) {
        String value = title == null ? "" : TRAILING.matcher(LEADING.matcher(title).replaceAll("")).replaceAll("");
        int length = value.codePointCount(0, value.length());
        if (length == 0 || length > max || value.contains("\n") || value.contains("\r")) throw invalid();
        return value;
    }

    /** 正文：纯文本，保留换行，统一为 \n，去掉末尾空白，1–5000 个字符。 */
    static String body(String body) {
        String value = body == null ? "" : TRAILING.matcher(body.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        int length = value.codePointCount(0, value.length());
        if (value.isBlank() || length > BODY_MAX) throw invalid();
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在或已删除");
    }
}
