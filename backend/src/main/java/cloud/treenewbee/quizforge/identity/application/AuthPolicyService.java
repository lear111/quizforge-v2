package cloud.treenewbee.quizforge.identity.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.PolicyMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.PolicyMapper.PolicyRow;
import cloud.treenewbee.quizforge.identity.verification.CaptchaVerifier;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 登录与注册设置（AUTH_POLICY.md）：五个独立开关，修改带版本号并在同一事务内记审计；
 * 启用尚未配置的验证服务会被拒绝；故障恢复命令只关闭两项登录额外验证。
 */
@Service
public class AuthPolicyService {

    /** 登录与注册设置。 */
    public record Policy(boolean loginCodeRequired, boolean loginCaptchaRequired, boolean registrationCodeRequired,
                         boolean registrationCaptchaRequired, boolean invitationRequired, long revision) {

        static Policy from(PolicyRow row) {
            return new Policy(row.loginCode(), row.loginCaptcha(), row.registrationCode(),
                    row.registrationCaptcha(), row.invitationRequired(), row.revision());
        }

        PolicyRow row() {
            return new PolicyRow(loginCodeRequired, loginCaptchaRequired, registrationCodeRequired,
                    registrationCaptchaRequired, invitationRequired, revision);
        }

        Map<String, Object> flags() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("loginCodeRequired", loginCodeRequired);
            value.put("loginCaptchaRequired", loginCaptchaRequired);
            value.put("registrationCodeRequired", registrationCodeRequired);
            value.put("registrationCaptchaRequired", registrationCaptchaRequired);
            value.put("invitationRequired", invitationRequired);
            return value;
        }
    }

    /** 修改请求：null 表示不修改该项。 */
    public record Change(Boolean loginCodeRequired, Boolean loginCaptchaRequired, Boolean registrationCodeRequired,
                         Boolean registrationCaptchaRequired, Boolean invitationRequired) {

        boolean empty() {
            return loginCodeRequired == null && loginCaptchaRequired == null && registrationCodeRequired == null
                    && registrationCaptchaRequired == null && invitationRequired == null;
        }
    }

    /** 服务可用性，不含任何密钥。 */
    public record Capabilities(boolean emailCodeAvailable, boolean phoneCodeAvailable, boolean captchaAvailable,
                               boolean loginCodeAvailable) {
    }

    private final PolicyMapper policies;
    private final AccountMapper accounts;
    private final VerificationCodeService codes;
    private final CaptchaVerifier captcha;
    private final AccountAuditService audit;
    private final Clock clock;

    public AuthPolicyService(PolicyMapper policies, AccountMapper accounts, VerificationCodeService codes,
                             CaptchaVerifier captcha, AccountAuditService audit, Clock clock) {
        this.policies = policies;
        this.accounts = accounts;
        this.codes = codes;
        this.captcha = captcha;
        this.audit = audit;
        this.clock = clock;
    }

    public Policy current() {
        return Policy.from(policies.get());
    }

    public Capabilities capabilities() {
        boolean email = codes.channelAvailable(Contact.Channel.EMAIL);
        boolean phone = codes.channelAvailable(Contact.Channel.PHONE);
        // 开启登录验证码前，已有账号的每一种联系方式都必须能收到验证码，否则会锁住这些用户。
        boolean loginCode = (email || phone)
                && (email || !accounts.anyEmailAccount())
                && (phone || !accounts.anyPhoneAccount());
        return new Capabilities(email, phone, captcha.ready(), loginCode);
    }

    /** 管理员修改设置；revision 必须等于当前版本，否则 409。 */
    @Transactional
    public Policy update(String actorId, Change change, long revision) {
        if (change.empty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "验证选项必须明确设置为开启或关闭");
        }
        PolicyRow previousRow = policies.lock();
        Policy previous = Policy.from(previousRow);
        if (previous.revision() != revision) {
            throw new ApiException(HttpStatus.CONFLICT, "POLICY_CONFLICT", "设置已被其他管理员更新，请重新读取后再保存");
        }
        Policy next = new Policy(
                pick(change.loginCodeRequired(), previous.loginCodeRequired()),
                pick(change.loginCaptchaRequired(), previous.loginCaptchaRequired()),
                pick(change.registrationCodeRequired(), previous.registrationCodeRequired()),
                pick(change.registrationCaptchaRequired(), previous.registrationCaptchaRequired()),
                pick(change.invitationRequired(), previous.invitationRequired()),
                previous.revision());
        Capabilities available = capabilities();
        requireReady(next.loginCodeRequired() && !previous.loginCodeRequired(), available.loginCodeAvailable());
        requireReady(next.registrationCodeRequired() && !previous.registrationCodeRequired(),
                available.emailCodeAvailable() || available.phoneCodeAvailable());
        requireReady(next.loginCaptchaRequired() && !previous.loginCaptchaRequired(), available.captchaAvailable());
        requireReady(next.registrationCaptchaRequired() && !previous.registrationCaptchaRequired(),
                available.captchaAvailable());
        return apply(actorId, previous, next, "authentication-policy-updated");
    }

    /** 服务器命令行的故障恢复：只关闭登录验证码与登录人机验证，保留注册设置与账号数据。 */
    @Transactional
    public Policy loginRecovery(String actorId) {
        Policy previous = Policy.from(policies.lock());
        Policy next = new Policy(false, false, previous.registrationCodeRequired(),
                previous.registrationCaptchaRequired(), previous.invitationRequired(), previous.revision());
        return apply(actorId, previous, next, "authentication-login-recovery");
    }

    private Policy apply(String actorId, Policy previous, Policy next, String action) {
        if (next.flags().equals(previous.flags())) return previous;
        if (policies.update(next.row(), previous.revision(), clock.instant()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "POLICY_CONFLICT", "设置已被其他管理员更新，请重新读取后再保存");
        }
        Policy saved = current();
        audit.record(actorId, "authentication", action, Map.of("previous", previous.flags(), "replacement",
                saved.flags(), "revision", saved.revision()));
        return saved;
    }

    private static boolean pick(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static void requireReady(boolean enabling, boolean ready) {
        if (enabling && !ready) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_NOT_CONFIGURED", "请先配置所需的验证码或人机验证服务");
        }
    }
}
