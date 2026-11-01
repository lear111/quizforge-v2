package cloud.treenewbee.quizforge.identity.verification;

import java.net.URI;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 {@code qf.verification.mode} 选择实现。假实现只允许在本机或测试域名下启用，
 * 防止误配置后在线上用固定令牌通过人机验证。
 */
@Configuration
public class VerificationConfig {

    private static final Logger log = LoggerFactory.getLogger(VerificationConfig.class);

    @Bean
    VerificationServices verificationServices(VerificationProperties properties, Clock clock,
                                              @Value("${qf.security.allowed-origin}") String origin) {
        if (properties.fake()) {
            if (!localOrTestOrigin(origin)) {
                throw new IllegalStateException("qf.verification.mode=fake is only allowed for local or .test origins");
            }
            log.warn("验证码使用开发假实现：不发送真实邮件或短信，验证码写入日志");
            FakeVerification fake = new FakeVerification();
            return new VerificationServices(fake, fake);
        }
        ProviderVerification provider = new ProviderVerification(properties, clock);
        return new VerificationServices(provider, provider);
    }

    // 两个接口由同一个对象实现；各自用委托包装注册，避免按类型注入时出现两个候选。
    @Bean
    VerificationSender verificationSender(VerificationServices services) {
        VerificationSender target = services.sender();
        return new VerificationSender() {
            @Override
            public boolean available(cloud.treenewbee.quizforge.identity.domain.Contact.Channel channel) {
                return target.available(channel);
            }

            @Override
            public String pepper(cloud.treenewbee.quizforge.identity.domain.Contact.Channel channel) {
                return target.pepper(channel);
            }

            @Override
            public void send(cloud.treenewbee.quizforge.identity.domain.Contact contact, String code,
                             VerificationPurpose purpose, String requestId) {
                target.send(contact, code, purpose, requestId);
            }
        };
    }

    @Bean
    CaptchaVerifier captchaVerifier(VerificationServices services) {
        CaptchaVerifier target = services.captcha();
        return new CaptchaVerifier() {
            @Override
            public boolean ready() {
                return target.ready();
            }

            @Override
            public String provider() {
                return target.provider();
            }

            @Override
            public String siteKey() {
                return target.siteKey();
            }

            @Override
            public boolean verify(String token, String remoteIp, String hostname, String action) {
                return target.verify(token, remoteIp, hostname, action);
            }
        };
    }

    /** 当前选用的实现。测试通过 {@link #fake()} 读取假实现记录的验证码。 */
    public record VerificationServices(VerificationSender sender, CaptchaVerifier captcha) {

        public FakeVerification fake() {
            if (sender instanceof FakeVerification fake) return fake;
            throw new IllegalStateException("Fake verification is not enabled");
        }
    }

    static boolean localOrTestOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.endsWith(".localhost") || host.endsWith(".test"));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
