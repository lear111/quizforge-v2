package cloud.treenewbee.quizforge.identity.web;

import java.util.regex.Pattern;

import org.hibernate.validator.constraints.CodePointLength;

import cloud.treenewbee.quizforge.identity.domain.Contact;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 账号相关请求体。账号写作 identifier（邮箱或手机号），也兼容旧接口的 email / phone 字段，三者只能填一个。
 * 联系方式的格式校验与规范化在 {@link Contact} 中完成。
 */
public final class AuthRequests {

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    private static final int CONTACT_MAX = 200;
    private static final int CODE_MAX = 16;
    private static final int CAPTCHA_MAX = 2048;
    private static final int INVITATION_MAX = 64;

    private AuthRequests() {
    }

    public record Register(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            @NotBlank @CodePointLength(min = 1, max = 64) String name,
            @NotNull @CodePointLength(min = 8, max = 128) String password,
            @Size(max = CODE_MAX) String code,
            @Size(max = CAPTCHA_MAX) String captchaToken,
            @Size(max = INVITATION_MAX) String invitationCode) {
        public Register {
            name = trim(name);
        }

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    public record Login(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            // 登录不校验最短长度：旧账号可能使用短于 8 位的密码（C-06）；上限只为限制 scrypt 输入。
            @NotEmpty @CodePointLength(max = 1024) String password,
            @Size(max = CODE_MAX) String code,
            @Size(max = CAPTCHA_MAX) String captchaToken) {

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    /** 注册验证码（/api/auth/email-code、phone-code）。 */
    public record RegistrationCode(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            @Size(max = CAPTCHA_MAX) String captchaToken,
            @Size(max = INVITATION_MAX) String invitationCode) {

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    /** 登录验证码：先核对账号与密码。 */
    public record LoginCode(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            @NotEmpty @CodePointLength(max = 1024) String password,
            @Size(max = CAPTCHA_MAX) String captchaToken) {

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    public record PasswordResetCode(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            @Size(max = CAPTCHA_MAX) String captchaToken) {

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    public record PasswordReset(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            @NotNull @Size(max = CODE_MAX) String code,
            @NotNull @CodePointLength(min = 8, max = 128) String password) {

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    /** 绑定 / 更换联系方式：当前密码 + 目标联系方式 +（发码时）人机验证 /（确认时）验证码。 */
    public record ContactChange(
            @Size(max = CONTACT_MAX) String identifier,
            @Size(max = CONTACT_MAX) String email,
            @Size(max = CONTACT_MAX) String phone,
            @NotEmpty @CodePointLength(max = 1024) String password,
            @Size(max = CODE_MAX) String code,
            @Size(max = CAPTCHA_MAX) String captchaToken) {

        Contact contact() {
            return Contact.fromRequest(identifier, email, phone);
        }
    }

    /** 管理员修改登录与注册设置；revision 必填。 */
    public record PolicyUpdate(
            Boolean loginCodeRequired,
            Boolean loginCaptchaRequired,
            Boolean registrationCodeRequired,
            Boolean registrationCaptchaRequired,
            Boolean invitationRequired,
            @NotNull Long revision) {
    }

    public record InvitationCreate(
            @Min(1) @Max(100) Integer count,
            @Min(1) @Max(365) Integer days) {
    }

    public record Block(
            @NotNull Boolean blocked,
            @Size(max = 400) String reason) {
    }

    static String trim(String value) {
        return value == null ? null : EDGE_WHITESPACE.matcher(value).replaceAll("");
    }
}
