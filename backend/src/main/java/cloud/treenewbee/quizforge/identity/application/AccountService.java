package cloud.treenewbee.quizforge.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.identity.application.VerificationCodeService.SendRequest;
import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.identity.domain.PasswordHasher;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.identity.verification.VerificationPurpose;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 已登录用户的账号资料、绑定与更换联系方式，以及未登录时的找回密码。
 *
 * <p>绑定联系方式固定要求当前密码、目标联系方式的验证码与人机验证，不受登录与注册设置影响
 * （AUTH_POLICY.md）。与旧版一致，只能补绑账号缺少的那一种联系方式，已绑定的不能更换
 * （作者决定，PR #88 D-2）。用户身份只来自登录会话。
 */
@Service
public class AccountService {

    /** 对外公开的账号资料，不含密码哈希。 */
    public record Profile(String id, String name, String email, String phone, boolean admin,
                          List<String> unverifiedContacts) {
    }

    private final UserMapper users;
    private final AccountMapper accounts;
    private final SessionMapper sessions;
    private final PasswordHasher hasher;
    private final AuthService auth;
    private final VerificationCodeService codes;
    private final Clock clock;

    public AccountService(UserMapper users, AccountMapper accounts, SessionMapper sessions, PasswordHasher hasher,
                          AuthService auth, VerificationCodeService codes, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.auth = auth;
        this.codes = codes;
        this.clock = clock;
    }

    public Profile profile(String userId) {
        UserRow user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"));
        String phone = accounts.findPhone(userId).map(AccountMapper.PhoneRow::phone).orElse(null);
        // 只认与当前联系方式一致的未验证标记。
        List<String> unverified = accounts.listUnverified(userId).stream()
                .filter(row -> row.contact().equals("email".equals(row.channel()) ? user.email() : phone))
                .map(AccountMapper.UnverifiedRow::channel)
                .toList();
        return new Profile(user.id(), user.name(), user.email(), phone, user.isAdmin(), unverified);
    }

    // ---------------------------------------------------------------- 绑定联系方式

    public void sendContactCode(String userId, Contact contact, String password, String captchaToken,
                                String remoteIp) {
        UserRow user = requirePassword(userId, password);
        requireBindable(user, contact);
        codes.send(new SendRequest(contact, VerificationPurpose.BIND, userId, remoteIp, captchaToken, true,
                "bind_contact", true));
    }

    public Profile bindContact(String userId, Contact contact, String password, String code) {
        UserRow user = requirePassword(userId, password);
        codes.consume(contact, VerificationPurpose.BIND, userId, code, captchaVerified -> {
            // 发码后管理员不可能放宽这项要求，但旧验证码若未经人机验证仍拒绝。
            if (!captchaVerified) {
                throw new ApiException(HttpStatus.CONFLICT, "POLICY_CHANGED", "请完成人机验证后重新获取验证码");
            }
            accounts.lockUser(userId);
            UserRow latest = users.findById(userId).orElseThrow(AccountService::stateChanged);
            if (!Objects.equals(latest.passwordHash(), user.passwordHash()) || accounts.isBlocked(userId)) {
                throw stateChanged();
            }
            requireBindable(latest, contact);
            Instant now = clock.instant();
            try {
                // 条件写入：只有该类联系方式仍为空时才绑定，并发绑定时只有一个成功。
                int bound = contact.isEmail() ? accounts.bindEmail(userId, contact.value(), now)
                        : accounts.insertPhone(userId, contact.value(), now);
                if (bound != 1) throw alreadyBound();
            } catch (DuplicateKeyException error) {
                if (!contact.isEmail() && accounts.findPhone(userId).isPresent()) throw alreadyBound();
                throw contactTaken();
            }
            accounts.clearUnverified(userId, contact.channel().key());
            return null;
        });
        return profile(userId);
    }

    private UserRow requirePassword(String userId, String password) {
        UserRow user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"));
        if (!hasher.verify(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WRONG_PASSWORD", "请填写当前账号的正确密码");
        }
        return user;
    }

    private void requireBindable(UserRow user, Contact contact) {
        String current = contact.isEmail() ? user.email()
                : accounts.findPhone(user.id()).map(AccountMapper.PhoneRow::phone).orElse(null);
        if (current != null) throw alreadyBound();
        if (auth.findByContact(contact).isPresent()) throw contactTaken();
    }

    // ---------------------------------------------------------------- 找回密码

    /** 找回密码：无论账号是否存在，响应都相同；账号不存在或被封禁时不真正发送。 */
    public void sendPasswordResetCode(Contact contact, String captchaToken, String remoteIp) {
        Optional<UserRow> user = auth.findByContact(contact);
        boolean eligible = user.isPresent() && !accounts.isBlocked(user.get().id());
        codes.send(new SendRequest(contact, VerificationPurpose.RESET_PASSWORD, resetSubject(user), remoteIp,
                captchaToken, true, "reset_password", eligible));
    }

    /** 用验证码设置新密码，成功后该账号的所有会话失效，需要重新登录。 */
    public void resetPassword(Contact contact, String code, String newPassword) {
        Optional<UserRow> user = auth.findByContact(contact);
        String passwordHash = hasher.hash(newPassword);
        codes.consume(contact, VerificationPurpose.RESET_PASSWORD, resetSubject(user), code, captchaVerified -> {
            UserRow target = user.orElseThrow(AccountService::stateChanged);
            if (accounts.isBlocked(target.id())) throw AuthService.invalidCredentials();
            if (accounts.updatePassword(target.id(), target.passwordHash(), passwordHash, AuthService.PASSWORD_ALGO,
                    clock.instant()) != 1) {
                throw stateChanged();
            }
            sessions.deleteByUserId(target.id());
            auth.markVerified(target.id(), contact, clock.instant());
            return null;
        });
    }

    private static String resetSubject(Optional<UserRow> user) {
        return user.map(AuthService::loginSubject).orElse("none");
    }

    private static ApiException alreadyBound() {
        return new ApiException(HttpStatus.CONFLICT, "CONTACT_ALREADY_BOUND", "此类联系方式已绑定，暂不支持换绑");
    }

    private static ApiException contactTaken() {
        return new ApiException(HttpStatus.CONFLICT, "CONTACT_TAKEN", "该联系方式无法绑定，请使用其他联系方式");
    }

    private static ApiException stateChanged() {
        return new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CHANGED", "账号状态已变化，请重新操作");
    }
}
