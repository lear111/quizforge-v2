package cloud.treenewbee.quizforge.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.identity.application.AuthPolicyService.Policy;
import cloud.treenewbee.quizforge.identity.application.VerificationCodeService.SendRequest;
import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.identity.domain.PasswordHasher;
import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionRow;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.identity.verification.VerificationPurpose;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 注册、登录与会话。登录与注册是否需要验证码、人机验证、邀请码由 {@link AuthPolicyService} 决定，
 * 并在最终写入的事务内重新读取：发码或计算密码之后管理员加强了要求时返回 409，不能凭旧页面绕过验证。
 */
@Service
public class AuthService {

    static final Duration IDLE_TIMEOUT = Duration.ofDays(14);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofDays(30);
    public static final String PASSWORD_ALGO = "scrypt-v1";

    /** 登录或注册成功的结果：token 放进 Cookie，userId 用于后续逻辑。 */
    public record AuthResult(String token, String userId) {
    }

    /** 注册请求。code、captchaToken、invitationCode 按当前设置可为空。 */
    public record Registration(Contact contact, String name, String password, String code, String captchaToken,
                               String invitationCode) {
    }

    /** 登录请求。code、captchaToken 按当前设置可为空。 */
    public record Login(Contact contact, String password, String code, String captchaToken) {
    }

    private final UserMapper users;
    private final AccountMapper accounts;
    private final SessionMapper sessions;
    private final PasswordHasher hasher;
    private final AuthPolicyService policies;
    private final VerificationCodeService codes;
    private final InvitationService invitations;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AuthService(UserMapper users, AccountMapper accounts, SessionMapper sessions, PasswordHasher hasher,
                       AuthPolicyService policies, VerificationCodeService codes, InvitationService invitations,
                       Clock clock, TransactionTemplate transactions) {
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.policies = policies;
        this.codes = codes;
        this.invitations = invitations;
        this.clock = clock;
        this.transactions = transactions;
    }

    // ---------------------------------------------------------------- 注册

    /** 注册前获取验证码（仅在“注册需要验证码”开启时）。联系方式已被占用时不真正发送，响应相同。 */
    public void sendRegistrationCode(Contact contact, String invitationCode, String captchaToken, String remoteIp) {
        Policy policy = policies.current();
        if (!policy.registrationCodeRequired()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_NOT_REQUIRED", "注册当前无需验证码，请直接提交注册");
        }
        if (policy.invitationRequired()) invitations.requireUsable(invitationCode);
        boolean eligible = findByContact(contact).isEmpty();
        codes.send(new SendRequest(contact, VerificationPurpose.REGISTER, "", remoteIp, captchaToken,
                policy.registrationCaptchaRequired(), "register_" + contact.channel().key(), eligible));
    }

    public AuthResult register(Registration request, String remoteIp, String userAgent) {
        Contact contact = request.contact();
        Policy initial = policies.current();
        if ((initial.registrationCodeRequired() || initial.loginCodeRequired())
                && !codes.channelAvailable(contact.channel())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_CHANNEL_UNAVAILABLE",
                    "所选联系方式的验证服务暂未开放");
        }
        // 需要验证码时，人机验证已在发码时完成并随验证码保存。
        boolean captchaChecked = false;
        if (initial.registrationCaptchaRequired() && !initial.registrationCodeRequired()) {
            codes.requireCaptcha(request.captchaToken(), remoteIp, "register_" + contact.channel().key());
            captchaChecked = true;
        }
        if (initial.invitationRequired()) invitations.requireUsable(request.invitationCode());
        String passwordHash = hasher.hash(request.password());
        String userId = Ids.uuidV7();
        boolean codeVerified = initial.registrationCodeRequired();

        VerificationCodeService.VerifiedWork<AuthResult> create = captchaVerified -> {
            Policy latest = policies.current();
            if (latest.registrationCodeRequired() && !codeVerified
                    || latest.registrationCaptchaRequired() && !captchaVerified) {
                throw new ApiException(HttpStatus.CONFLICT, "POLICY_CHANGED", "注册设置已更新，请按最新要求完成验证");
            }
            if (findByContact(contact).isPresent()) throw taken(contact);
            Instant now = clock.instant();
            try {
                users.insert(new UserRow(userId, contact.isEmail() ? contact.value() : null, request.name(),
                        passwordHash, PASSWORD_ALGO, false, now, now, 0L));
                if (!contact.isEmail()) accounts.insertPhone(userId, contact.value(), codeVerified ? now : null);
            } catch (DuplicateKeyException error) {
                throw taken(contact);
            }
            // 关闭注册验证码时，联系方式明确标为未验证；不伪造验证时间。
            if (!codeVerified) accounts.markUnverified(userId, contact.channel().key(), contact.value());
            if (latest.invitationRequired()) invitations.consume(request.invitationCode(), userId);
            return createSession(userId, userAgent);
        };
        if (codeVerified) {
            return codes.consume(contact, VerificationPurpose.REGISTER, "", request.code(), create);
        }
        boolean captchaVerified = captchaChecked;
        return transactions.execute(status -> create.run(captchaVerified));
    }

    /** 邮箱注册的便捷入口（其他模块的测试使用）；仍受当前登录与注册设置约束。 */
    public AuthResult register(String email, String name, String password, String userAgent) {
        return register(new Registration(Contact.email(email), name, password, null, null, null), null, userAgent);
    }

    // ---------------------------------------------------------------- 登录

    /** 邮箱登录的便捷入口（其他模块的测试使用）；仍受当前登录与注册设置约束。 */
    public AuthResult login(String email, String password, String userAgent) {
        return login(new Login(Contact.email(email), password, null, null), null, userAgent);
    }

    /** 登录验证码：先核对账号与密码，再向该联系方式发码；不创建会话。 */
    public void sendLoginCode(Contact contact, String password, String captchaToken, String remoteIp) {
        Policy policy = policies.current();
        if (!policy.loginCodeRequired()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_NOT_REQUIRED", "登录当前无需验证码，请直接登录");
        }
        UserRow user = authenticate(contact, password);
        codes.send(new SendRequest(contact, VerificationPurpose.LOGIN, loginSubject(user), remoteIp, captchaToken,
                policy.loginCaptchaRequired(), "login_" + contact.channel().key(), true));
    }

    public AuthResult login(Login request, String remoteIp, String userAgent) {
        Contact contact = request.contact();
        Policy selected = policies.current();
        boolean captchaChecked = false;
        if (selected.loginCaptchaRequired() && !selected.loginCodeRequired()) {
            codes.requireCaptcha(request.captchaToken(), remoteIp, "login_" + contact.channel().key());
            captchaChecked = true;
        }
        UserRow user = authenticate(contact, request.password());

        VerificationCodeService.VerifiedWork<AuthResult> finish = captchaVerified -> {
            Policy current = policies.current();
            if (current.loginCodeRequired() && !selected.loginCodeRequired()
                    || current.loginCaptchaRequired() && !captchaVerified) {
                throw new ApiException(HttpStatus.CONFLICT, "POLICY_CHANGED", "登录设置已更新，请按最新要求完成验证");
            }
            // 期间密码被修改、账号被封禁或联系方式被换绑时拒绝。
            Optional<UserRow> latest = findByContact(contact);
            if (latest.isEmpty() || !latest.get().id().equals(user.id())
                    || !Objects.equals(latest.get().passwordHash(), user.passwordHash())
                    || accounts.isBlocked(user.id())) {
                throw invalidCredentials();
            }
            if (selected.loginCodeRequired()) markVerified(user.id(), contact, clock.instant());
            return createSession(user.id(), userAgent);
        };
        if (selected.loginCodeRequired()) {
            return codes.consume(contact, VerificationPurpose.LOGIN, loginSubject(user), request.code(), finish);
        }
        boolean captchaVerified = captchaChecked;
        return transactions.execute(status -> finish.run(captchaVerified));
    }

    // 退出登录
    public void logout(String rawToken) {
        if (!SessionToken.isWellFormed(rawToken)) {
            return;
        }
        sessions.deleteByHash(SessionToken.hash(rawToken));
    }

    // ---------------------------------------------------------------- 共用

    /**
     * 核对账号与密码。账号不存在、密码错误、账号被封禁返回相同的 401，且都完整计算一次 scrypt。
     */
    UserRow authenticate(Contact contact, String password) {
        Optional<UserRow> user = findByContact(contact);
        boolean matches = hasher.verify(password, user.map(UserRow::passwordHash).orElse(null));
        if (user.isEmpty() || !matches || accounts.isBlocked(user.get().id())) throw invalidCredentials();
        return user.get();
    }

    Optional<UserRow> findByContact(Contact contact) {
        return contact.isEmail() ? users.findByEmail(contact.value()) : accounts.findByPhone(contact.value());
    }

    /** 收到验证码即证明拥有该联系方式：清除“未验证”标记，手机号补记验证时间。 */
    void markVerified(String userId, Contact contact, Instant now) {
        accounts.clearUnverified(userId, contact.channel().key());
        if (!contact.isEmail()) accounts.markPhoneVerified(userId, contact.value(), now);
    }

    /** 登录与找回密码的验证码绑定账号与当前密码版本：改密后旧验证码失效。 */
    static String loginSubject(UserRow user) {
        return user.id() + ":" + sha256Hex(user.passwordHash());
    }

    AuthResult createSession(String userId, String userAgent) {
        String token = SessionToken.generate();
        Instant now = clock.instant();
        sessions.insert(new SessionRow(SessionToken.hash(token), userId, now, now, now.plus(IDLE_TIMEOUT),
                now.plus(ABSOLUTE_TIMEOUT), userAgent));
        return new AuthResult(token, userId);
    }

    static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码不正确，或账号已停用");
    }

    static ApiException taken(Contact contact) {
        return contact.isEmail()
                ? new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "该邮箱已注册")
                : new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN", "该手机号已注册");
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
