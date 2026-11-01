package cloud.treenewbee.quizforge.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/** 管理员身份检查、封禁与角色。角色只能通过服务器命令行修改，不提供公开接口。 */
@Service
public class AccountAdminService {

    static final int MAX_REASON = 200;

    private final UserMapper users;
    private final AccountMapper accounts;
    private final SessionMapper sessions;
    private final AuthService auth;
    private final AccountAuditService audit;
    private final Clock clock;

    public AccountAdminService(UserMapper users, AccountMapper accounts, SessionMapper sessions, AuthService auth,
                               AccountAuditService audit, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.audit = audit;
        this.clock = clock;
    }

    /** 当前会话用户必须是未被封禁的管理员。 */
    public void requireAdmin(String userId) {
        boolean admin = users.findById(userId).map(UserRow::isAdmin).orElse(false);
        if (!admin || accounts.isBlocked(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可以执行此操作");
        }
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
