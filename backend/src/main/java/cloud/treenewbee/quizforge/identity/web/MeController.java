package cloud.treenewbee.quizforge.identity.web;

import java.security.Principal;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.identity.application.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** 当前登录用户的资料与联系方式。用户身份只来自会话（Principal），不接受请求中的用户 ID。 */
@RestController
public class MeController {

    private final AccountService accounts;

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/me")
    public UserResponse me(Principal principal) {
        return UserResponse.from(accounts.profile(principal.getName()));
    }

    @PostMapping("/api/account/contact-code")
    public Map<String, Object> contactCode(@Valid @RequestBody AuthRequests.ContactChange body, Principal principal,
                                           HttpServletRequest request) {
        accounts.sendContactCode(principal.getName(), body.contact(), body.password(), body.captchaToken(),
                request.getRemoteAddr());
        return Map.of("message", "验证码已发送，请查收。", "retryAfter", 60);
    }

    @PostMapping("/api/account/contact")
    public UserResponse bindContact(@Valid @RequestBody AuthRequests.ContactChange body, Principal principal) {
        return UserResponse.from(accounts.bindContact(principal.getName(), body.contact(), body.password(),
                body.code()));
    }
}
