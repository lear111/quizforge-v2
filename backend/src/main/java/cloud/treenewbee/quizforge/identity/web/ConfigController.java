package cloud.treenewbee.quizforge.identity.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.client.application.ClientSettings;
import cloud.treenewbee.quizforge.identity.application.AuthPolicyService;
import cloud.treenewbee.quizforge.identity.verification.CaptchaVerifier;

/**
 * 公开当前的登录与注册要求，前端据此显示验证码、人机验证与邀请码输入项。不返回任何密钥。
 * 字段与旧平台的 /api/config 保持同名（authPolicy、verificationMethods、captchaReady、invitationRequired）。
 */
@RestController
public class ConfigController {

    private final AuthPolicyService policies;
    private final CaptchaVerifier captcha;
    private final ClientSettings client;

    public ConfigController(AuthPolicyService policies, CaptchaVerifier captcha, ClientSettings client) {
        this.policies = policies;
        this.captcha = captcha;
        this.client = client;
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        var policy = policies.current();
        var capabilities = policies.capabilities();
        boolean email = capabilities.emailCodeAvailable();
        boolean phone = capabilities.phoneCodeAvailable();
        // 需要发码时只开放已配置的通道；需要人机验证但服务缺失时注册不可用。
        boolean useCodes = policy.registrationCodeRequired() || policy.loginCodeRequired();
        boolean registerEmail = !useCodes || email;
        boolean registerPhone = !useCodes || phone;
        boolean ready = (!policy.registrationCaptchaRequired() || capabilities.captchaAvailable())
                && (registerEmail || registerPhone);

        Map<String, Object> authPolicy = new LinkedHashMap<>();
        authPolicy.put("loginCodeRequired", policy.loginCodeRequired());
        authPolicy.put("loginCaptchaRequired", policy.loginCaptchaRequired());
        authPolicy.put("registrationCodeRequired", policy.registrationCodeRequired());
        authPolicy.put("registrationCaptchaRequired", policy.registrationCaptchaRequired());
        authPolicy.put("invitationRequired", policy.invitationRequired());
        authPolicy.put("revision", policy.revision());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authPolicy", authPolicy);
        result.put("invitationRequired", policy.invitationRequired());
        result.put("registrationReady", ready);
        result.put("registrationMethods", Map.of("email", ready && registerEmail, "phone", ready && registerPhone));
        result.put("verificationMethods", Map.of("email", email, "phone", phone));
        result.put("captchaReady", capabilities.captchaAvailable());
        result.put("captchaProvider", captcha.provider());
        result.put("turnstileSiteKey", captcha.siteKey());
        // 遗留契约 C-05 第 15 节：客户端兼容能力（练习与后台都在本站，没有独立来源）。
        result.put("maxArchiveMB", 64);
        result.put("cloudEnabled", true);
        result.put("practiceOrigin", "");
        result.put("adminEnabled", true);
        result.put("localImport", Map.of("enabled", client.localImportEnabled(), "protocolVersion", 1, "scheme", "quizforge"));
        result.put("officialAi", Map.of("enabled", client.connectEnabled(), "protocolVersion", 1));
        return result;
    }
}
