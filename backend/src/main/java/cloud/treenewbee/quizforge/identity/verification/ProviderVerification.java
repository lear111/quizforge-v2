package cloud.treenewbee.quizforge.identity.verification;

import java.time.Clock;

import cloud.treenewbee.quizforge.identity.domain.Contact;

/** 生产实现：邮件走腾讯云 SES，短信走阿里云号码认证，人机验证走 Cloudflare Turnstile。 */
public class ProviderVerification implements VerificationSender, CaptchaVerifier {

    private final VerificationProperties properties;
    private final TencentSesEmailSender email;
    private final AliyunSmsSender sms;
    private final TurnstileVerifier turnstile;

    public ProviderVerification(VerificationProperties properties, Clock clock) {
        this.properties = properties;
        ProviderHttp http = new ProviderHttp();
        this.email = new TencentSesEmailSender(properties.tencent(), http, clock);
        this.sms = new AliyunSmsSender(properties.aliyun(), http, clock);
        this.turnstile = new TurnstileVerifier(properties.turnstile(), http);
    }

    @Override
    public boolean available(Contact.Channel channel) {
        boolean configured = channel == Contact.Channel.EMAIL ? email.configured() : sms.configured();
        return configured && pepper(channel).length() >= VerificationProperties.MIN_PEPPER_LENGTH;
    }

    @Override
    public String pepper(Contact.Channel channel) {
        return channel == Contact.Channel.EMAIL ? properties.emailPepper() : properties.smsPepper();
    }

    @Override
    public void send(Contact contact, String code, VerificationPurpose purpose, String requestId) {
        if (!available(contact.channel())) throw new VerificationUnavailableException("Channel not configured");
        if (contact.isEmail()) email.send(contact.value(), code, purpose);
        else sms.send(contact.value(), code, requestId);
    }

    @Override
    public boolean ready() {
        return turnstile.configured();
    }

    @Override
    public String provider() {
        return ready() ? "turnstile" : "";
    }

    @Override
    public String siteKey() {
        return ready() ? properties.turnstile().siteKey() : "";
    }

    @Override
    public boolean verify(String token, String remoteIp, String hostname, String action) {
        return turnstile.verify(token, remoteIp, hostname, action);
    }
}
