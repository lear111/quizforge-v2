package cloud.treenewbee.quizforge.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserAdminMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import cloud.treenewbee.quizforge.shared.web.KeysetCursor;

/** 管理员身份检查、封禁与角色。角色只能通过服务器命令行修改，不提供公开接口。 */
@Service
public class AccountAdminService {

    static final int MAX_REASON = 200;
    public static final int MAX_PAGE = 50;
    static final int MAX_QUERY = 100;

    /** 用户列表中的一行。 */
    public record UserSummary(String id, String name, String email, String phone, boolean admin, boolean blocked,
                              Instant createdAt) {
    }

    public record UserPage(List<UserSummary> items, String nextCursor) {
    }

    /** 联系方式及其验证状态。 */
    public record ContactState(String value, boolean verified, Instant verifiedAt) {
    }

    public record BlockState(String reason, String actorId, String actorName, Instant createdAt) {
    }

    /** 用户详情（不含密码哈希）。 */
    public record UserDetail(String id, String name, ContactState email, ContactState phone, boolean admin,
                             Instant createdAt, BlockState block) {
    }

    private final UserMapper users;
    private final UserAdminMapper userAdmin;
    private final AccountMapper accounts;
    private final SessionMapper sessions;
    private final AuthService auth;
    private final AccountAuditService audit;
    private final Clock clock;

    public AccountAdminService(UserMapper users, UserAdminMapper userAdmin, AccountMapper accounts,
                               SessionMapper sessions, AuthService auth, AccountAuditService audit, Clock clock) {
        this.users = users;
        this.userAdmin = userAdmin;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.audit = audit;
        this.clock = clock;
    }

    /** 后台用户列表：按注册时间倒序的键集分页，每页最多 50 条；q 匹配邮箱、手机号或昵称。 */
    public UserPage search(String query, String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        String q = query == null ? "" : query.strip();
        if (q.codePointCount(0, q.length()) > MAX_QUERY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        }
        String pattern = q.isEmpty() ? null
                : "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        KeysetCursor after = KeysetCursor.decode(cursor);
        var rows = userAdmin.page(pattern, after == null ? null : after.time(), after == null ? null : after.id(),
                size + 1);
        List<UserSummary> items = rows.stream().limit(size).map(row -> new UserSummary(row.id(), row.name(),
                row.email(), row.phone(), row.isAdmin(), row.blocked(), row.createdAt())).toList();
        String next = rows.size() > size
                ? KeysetCursor.encode(items.getLast().createdAt(), items.getLast().id()) : null;
        return new UserPage(items, next);
    }

    public UserDetail detail(String userId) {
        var row = userAdmin.detail(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        ContactState email = row.email() == null ? null : new ContactState(row.email(), !row.emailUnverified(), null);
        ContactState phone = row.phone() == null ? null
                : new ContactState(row.phone(), Boolean.TRUE.equals(row.phoneVerified()), row.phoneVerifiedAt());
        BlockState block = row.blockedAt() == null ? null
                : new BlockState(row.blockReason(), row.blockActorId(), row.blockActorName(), row.blockedAt());
        return new UserDetail(row.id(), row.name(), email, phone, row.isAdmin(), row.createdAt(), block);
    }

    public long userCount() {
        return userAdmin.count();
    }

    /** 是否为未被封禁的管理员（每次请求从数据库读取，撤销管理员或封禁立即生效）。 */
    public boolean isAdmin(String userId) {
        return users.findById(userId).map(UserRow::isAdmin).orElse(false) && !accounts.isBlocked(userId);
    }

    /** 当前会话用户必须是未被封禁的管理员。 */
    public void requireAdmin(String userId) {
        if (!isAdmin(userId)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可以执行此操作");
    }

    /**
     * 封禁或解封。封禁立即生效：删除该用户全部会话，撤销设备令牌与未使用的设备授权码；
     * 会话校验本身也排除被封禁用户，两者任一生效都能阻止访问。
     */
    @Transactional
    public void setBlocked(String actorId, String userId, boolean blocked, String reason) {
        if (actorId.equals(userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "不能封禁当前管理员");
        }
        String note = reason == null ? "" : reason.strip();
        if (note.codePointCount(0, note.length()) > MAX_REASON) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "原因最多 200 个字");
        }
        if (users.findById(userId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        accounts.lockUser(userId);
        Instant now = clock.instant();
        if (blocked) {
            accounts.block(userId, actorId, note, now);
            sessions.deleteByUserId(userId);
            accounts.revokeDevices(userId, now);
            accounts.deleteConnectCodes(userId);
        } else {
            accounts.unblock(userId);
        }
        audit.record(actorId, userId, blocked ? "user-blocked" : "user-unblocked", Map.of("reason", note));
    }

    /** 服务器命令行：按邮箱或手机号设置或取消管理员。 */
    @Transactional
    public String setAdmin(String identifier, boolean admin) {
        Contact contact = Contact.identifier(identifier);
        UserRow user = auth.findByContact(contact)
                .orElseThrow(() -> new IllegalArgumentException("找不到该账号，请先在网站注册"));
        accounts.lockUser(user.id());
        accounts.setAdmin(user.id(), admin, clock.instant());
        audit.record(null, user.id(), admin ? "local-role-promote" : "local-role-demote",
                Map.of("admin", admin, "source", "local-command"));
        return user.id();
    }

    /** 服务器命令行的故障恢复命令要求指定一个已有的管理员账号。 */
    public String requireExistingAdmin(String identifier) {
        UserRow user = auth.findByContact(Contact.identifier(identifier))
                .orElseThrow(() -> new IllegalArgumentException("找不到该账号"));
        if (!user.isAdmin()) throw new IllegalArgumentException("该账号不是管理员");
        return user.id();
    }
}
