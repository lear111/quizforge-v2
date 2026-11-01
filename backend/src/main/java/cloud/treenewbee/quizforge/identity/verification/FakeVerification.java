package cloud.treenewbee.quizforge.identity.verification;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cloud.treenewbee.quizforge.identity.domain.Contact;

/**
 * 开发与测试用的假实现：不访问网络，验证码写入日志并保存在内存中供测试读取。
 *
 * <p>只能在 {@code qf.verification.mode=fake} 且站点来源为本机或测试域名时启用
 * （{@link VerificationConfig}），CI 不发送真实短信或邮件。
 */
public class FakeVerification implements VerificationSender, CaptchaVerifier {

    /** 假人机验证接受的令牌。 */
    public static final String CAPTCHA_TOKEN = "fake-captcha-pass";
    private static final String PEPPER = "quizforge-fake-verification-pepper-0001";
    private static final Logger log = LoggerFactory.getLogger(FakeVerification.class);

    public record Sent(Contact contact, String code, VerificationPurpose purpose) {
    }

    private final List<Sent> outbox = new CopyOnWriteArrayList<>();
    private final Set<Contact.Channel> disabled = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> failures = new ConcurrentHashMap<>();
    private volatile boolean captchaReady = true;

    @Override
    public boolean available(Contact.Channel channel) {
        return !disabled.contains(channel);
    }

    @Override
    public String pepper(Contact.Channel channel) {
        return PEPPER;
    }

    @Override
    public void send(Contact contact, String code, VerificationPurpose purpose, String requestId) {
        if (!available(contact.channel())) throw new VerificationUnavailableException("Fake channel disabled");
        if (failures.remove(contact.value()) != null) throw new VerificationUnavailableException("Fake send failure");
        outbox.add(new Sent(contact, code, purpose));
        log.info("[开发验证码] {} {}验证码：{}", contact.masked(), purpose.label(), code);
    }

    @Override
    public boolean ready() {
        return captchaReady;
    }

    @Override
    public String provider() {
        return captchaReady ? "fake" : "";
    }

    @Override
    public String siteKey() {
        return captchaReady ? "fake-site-key" : "";
    }

    @Override
    public boolean verify(String token, String remoteIp, String hostname, String action) {
        return captchaReady && CAPTCHA_TOKEN.equals(token);
    }

    // ---- 测试辅助 ----

    /** 发给该联系方式的最后一个验证码；没有时返回 null。 */
    public String lastCode(String contactValue) {
        for (int i = outbox.size() - 1; i >= 0; i--) {
            if (outbox.get(i).contact().value().equals(contactValue)) return outbox.get(i).code();
        }
        return null;
    }

    public List<Sent> sent() {
        return List.copyOf(outbox);
    }

    public void setAvailable(Contact.Channel channel, boolean available) {
        if (available) disabled.remove(channel);
        else disabled.add(channel);
    }

    public void setCaptchaReady(boolean ready) {
        captchaReady = ready;
    }

    /** 让下一次发往该联系方式的请求失败一次。 */
    public void failNext(String contactValue) {
        failures.put(contactValue, true);
    }

    public void reset() {
        outbox.clear();
        disabled.removeAll(EnumSet.allOf(Contact.Channel.class));
        failures.clear();
        captchaReady = true;
    }
}
