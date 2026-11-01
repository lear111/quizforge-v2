package cloud.treenewbee.quizforge.identity.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.identity.application.AccountService;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.identity.application.AuthService.AuthResult;
import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int USER_AGENT_LENGTH = 255;
    private static final int RETRY_AFTER_SECONDS = 60;
    private final AuthService auth;
    private final AccountService accounts;

    public AuthController(AuthService auth, AccountService accounts) {
        this.auth = auth;
        this.accounts = accounts;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody AuthRequests.Register body,
                                                 HttpServletRequest request) {
        var registration = new AuthService.Registration(body.contact(), body.name(), body.password(), body.code(),
                body.captchaToken(), body.invitationCode());
        return authenticated(auth.register(registration, request.getRemoteAddr(), userAgent(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody AuthRequests.Login body,
                                              HttpServletRequest request) {
        var login = new AuthService.Login(body.contact(), body.password(), body.code(), body.captchaToken());
        return authenticated(auth.login(login, request.getRemoteAddr(), userAgent(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(SessionCookies.read(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, SessionCookies.clear().toString()).build();
    }

    @PostMapping("/email-code")
    public Map<String, Object> emailCode(@Valid @RequestBody AuthRequests.RegistrationCode body,
                                         HttpServletRequest request) {
        return registrationCode(body, Contact.Channel.EMAIL, request,
                "如果该邮箱可以注册，验证码将发送到邮箱，请检查收件箱和垃圾邮件。");
    }

    @PostMapping("/phone-code")
    public Map<String, Object> phoneCode(@Valid @RequestBody AuthRequests.RegistrationCode body,
                                         HttpServletRequest request) {
        return registrationCode(body, Contact.Channel.PHONE, request, "如果该手机号可以注册，验证码将通过短信发送，请注意查收。");
    }

    @PostMapping("/login-code")
    public Map<String, Object> loginCode(@Valid @RequestBody AuthRequests.LoginCode body,
                                         HttpServletRequest request) {
        auth.sendLoginCode(body.contact(), body.password(), body.captchaToken(), request.getRemoteAddr());
        return sent("验证码已发送，请查收。");
    }

    @PostMapping("/password-reset-code")
    public Map<String, Object> passwordResetCode(@Valid @RequestBody AuthRequests.PasswordResetCode body,
                                                 HttpServletRequest request) {
        accounts.sendPasswordResetCode(body.contact(), body.captchaToken(), request.getRemoteAddr());
        return sent("如果该账号存在，验证码将发送到对应的邮箱或手机，请注意查收。");
    }

    @PostMapping("/password-reset")
    public ResponseEntity<Void> passwordReset(@Valid @RequestBody AuthRequests.PasswordReset body) {
        accounts.resetPassword(body.contact(), body.code(), body.password());
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> registrationCode(AuthRequests.RegistrationCode body, Contact.Channel channel,
                                                 HttpServletRequest request, String message) {
        Contact contact = body.contact();
        if (contact.channel() != channel) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        }
        auth.sendRegistrationCode(contact, body.invitationCode(), body.captchaToken(), request.getRemoteAddr());
        return sent(message);
    }

    private static Map<String, Object> sent(String message) {
        return Map.of("message", message, "retryAfter", RETRY_AFTER_SECONDS);
    }

    private ResponseEntity<UserResponse> authenticated(AuthResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.create(result.token()).toString())
                .body(UserResponse.from(accounts.profile(result.userId())));
    }

    private static String userAgent(HttpServletRequest request) {
        String value = request.getHeader(HttpHeaders.USER_AGENT);
        if (value == null) {
            return null;
        }
        int length = Math.min(value.codePointCount(0, value.length()), USER_AGENT_LENGTH);
        return value.substring(0, value.offsetByCodePoints(0, length));
    }
}
