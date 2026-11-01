package cloud.treenewbee.quizforge.identity.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.identity.domain.Contact;
import cloud.treenewbee.quizforge.identity.infrastructure.VerificationMapper;
import cloud.treenewbee.quizforge.identity.verification.CaptchaVerifier;
import cloud.treenewbee.quizforge.identity.verification.VerificationProperties;
import cloud.treenewbee.quizforge.identity.verification.VerificationPurpose;
import cloud.treenewbee.quizforge.identity.verification.VerificationSender;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 邮箱与短信验证码，规则沿用旧平台（AUTH_POLICY.md、PHONE_AUTH.md）：
 *
 * <ul>
 *   <li>六位数字，十分钟有效，最多五次错误；重发替换旧码并清零错误次数；只消费一次。</li>
 *   <li>用途隔离：摘要的主体是 HMAC(用途 + 绑定对象 + 联系方式)，注册、登录、绑定、找回密码互不通用；
 *       登录与找回密码的验证码还绑定账号与当前密码版本。</li>
 *   <li>发送频率：同一地址 60 秒 1 次、24 小时 5 次；同一 IP 10 分钟 5 次、24 小时 20 次；
 *       全站每通道 24 小时上限（短信默认 100，可配置）。超出时发送预留不退还。</li>
 *   <li>数据库只存 HMAC 摘要，不存验证码、联系方式与 IP 的明文。</li>
 * </ul>
 */
@Service
public class VerificationCodeService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    private static final Pattern CODE_FORMAT = Pattern.compile("[0-9]{6}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);

    /** 消费验证码后、在同一事务内执行的业务操作；参数为发码时是否已通过人机验证。 */
    @FunctionalInterface
    public interface VerifiedWork<T> {
        T run(boolean captchaVerified);
    }

    /** 发码请求。subject 为绑定对象（账号与密码版本等），注册时为空串。 */
    public record SendRequest(Contact contact, VerificationPurpose purpose, String subject, String remoteIp,
                              String captchaToken, boolean captchaRequired, String captchaAction,
                              boolean eligible) {
    }

    private record Keys(String channel, String pepper, String addressKey, String subjectKey, String ipKey) {
    }

    private record Outcome<T>(T value, ApiException error) {
    }

    private final VerificationMapper mapper;
    private final VerificationSender sender;
    private final CaptchaVerifier captcha;
    private final VerificationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String hostname;

    public VerificationCodeService(VerificationMapper mapper, VerificationSender sender, CaptchaVerifier captcha,
                                   VerificationProperties properties, TransactionTemplate transactions, Clock clock,
                                   @Value("${qf.security.allowed-origin}") String origin) {
        this.mapper = mapper;
        this.sender = sender;
        this.captcha = captcha;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.hostname = URI.create(origin).getHost();
    }

    public boolean channelAvailable(Contact.Channel channel) {
        return sender.available(channel);
    }

    public void requireChannel(Contact.Channel channel) {
        if (!sender.available(channel)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_CHANNEL_UNAVAILABLE",
                    channel == Contact.Channel.PHONE ? "手机验证暂未开放，请使用邮箱" : "邮箱验证暂未开放，请稍后再试");
        }
    }

    /** 校验人机验证令牌，失败时抛出 400。 */
    public void requireCaptcha(String token, String remoteIp, String action) {
        boolean valid;
        try {
            valid = captcha.verify(token, remoteIp, hostname, action);
        } catch (RuntimeException error) {
            valid = false;
        }
        if (!valid) throw new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_FAILED", "人机验证未通过，请重试");
    }

    /**
     * 发送验证码。eligible 为 false 时（例如注册时联系方式已被占用）照常记录频率与验证码行，
     * 但不真正发送，避免通过响应差异判断账号是否存在。
     */
    public void send(SendRequest request) {
        requireChannel(request.contact().channel());
        Keys keys = keys(request.contact(), request.purpose(), request.subject(), request.remoteIp());
        transactions.executeWithoutResult(status -> checkLimits(keys, clock.instant()));
        boolean captchaVerified = false;
        if (request.captchaRequired()) {
            requireCaptcha(request.captchaToken(), request.remoteIp(), request.captchaAction());
            captchaVerified = true;
        }
        String nonce = Ids.uuidV7();
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        byte[] digest = digest(keys, nonce, code);
        boolean verified = captchaVerified;
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            mapper.lockChannel(keys.channel());
            checkLimits(keys, now);
            mapper.deleteSendsBefore(keys.channel(), now.minus(Duration.ofDays(1)));
            mapper.deleteExpired(now);
            mapper.insertSend(nonce, keys.channel(), keys.addressKey(), keys.ipKey(), now);
            mapper.upsert(keys.channel(), keys.subjectKey(), nonce, digest, now.plus(CODE_TTL), verified);
        });
        if (!request.eligible()) {
            mapper.markSent(keys.channel(), keys.subjectKey(), nonce, "failed");
            return;
        }
        try {
            sender.send(request.contact(), code, request.purpose(), nonce);
            mapper.markSent(keys.channel(), keys.subjectKey(), nonce, "sent");
        } catch (RuntimeException error) {
            mapper.markSent(keys.channel(), keys.subjectKey(), nonce, "failed");
            log.warn("Verification code delivery failed for {} ({})", request.contact().masked(), error.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_UNAVAILABLE",
                    "验证码暂时无法发送，请稍后重新获取");
        }
    }

    /**
     * 校验并消费验证码，成功后在同一事务内执行 work；work 失败时整体回滚，验证码不被消费。
     * 验证码错误时错误次数在独立提交后再报错。
     */
    public <T> T consume(Contact contact, VerificationPurpose purpose, String subject, String code,
                         VerifiedWork<T> work) {
        requireChannel(contact.channel());
        Keys keys = keys(contact, purpose, subject, "");
        String raw = code == null ? "" : code.trim();
        Outcome<T> outcome = transactions.execute(status -> {
            Instant now = clock.instant();
            if (mapper.find(keys.channel(), keys.subjectKey()).isEmpty()) return new Outcome<T>(null, invalid());
            var row = mapper.lock(keys.channel(), keys.subjectKey()).orElse(null);
            if (row == null || !"sent".equals(row.state()) || !row.expiresAt().isAfter(now)
                    || row.attempts() >= MAX_ATTEMPTS) {
                return new Outcome<T>(null, invalid());
            }
            if (!CODE_FORMAT.matcher(raw).matches()
                    || !MessageDigest.isEqual(digest(keys, row.nonce(), raw), row.digest())) {
                mapper.recordFailure(keys.channel(), keys.subjectKey(), row.nonce());
                return new Outcome<T>(null, new ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_MISMATCH",
                        "验证码不正确，请重新输入"));
            }
            T value = work.run(row.captchaVerified());
            if (mapper.consume(keys.channel(), keys.subjectKey(), row.nonce()) != 1) {
                throw new IllegalStateException("Verification code changed while locked");
            }
            return new Outcome<T>(value, null);
        });
        if (outcome.error() != null) throw outcome.error();
        return outcome.value();
    }

    private void checkLimits(Keys keys, Instant now) {
        if (mapper.countByAddress(keys.channel(), keys.addressKey(), now.minus(Duration.ofSeconds(60))) >= 1
                || mapper.countByAddress(keys.channel(), keys.addressKey(), now.minus(Duration.ofDays(1))) >= 5
                || mapper.countByIp(keys.channel(), keys.ipKey(), now.minus(Duration.ofMinutes(10))) >= 5
                || mapper.countByIp(keys.channel(), keys.ipKey(), now.minus(Duration.ofDays(1))) >= 20) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RATE_LIMITED",
                    "验证码发送太频繁，请稍后重试");
        }
        int daily = keys.channel().equals("phone") ? properties.smsDailyLimit() : properties.emailDailyLimit();
        if (mapper.countByChannel(keys.channel(), now.minus(Duration.ofDays(1))) >= daily) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_DAILY_LIMIT",
                    "今天的验证发送额度已用完，请稍后再来");
        }
    }

    private Keys keys(Contact contact, VerificationPurpose purpose, String subject, String remoteIp) {
        String pepper = sender.pepper(contact.channel());
        String channel = contact.channel().key();
        return new Keys(channel, pepper,
                hmacHex(pepper, contact.value()),
                hmacHex(pepper, purpose.key() + ":" + subject + ":" + contact.value()),
                hmacHex(pepper, remoteIp == null ? "" : remoteIp));
    }

    private static byte[] digest(Keys keys, String nonce, String code) {
        return hmac(keys.pepper(), nonce + ":" + keys.subjectKey() + ":" + code);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_INVALID", "验证码无效或已失效，请重新获取");
    }

    static String hmacHex(String key, String value) {
        return HexFormat.of().formatHex(hmac(key, value));
    }

    private static byte[] hmac(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.isEmpty() ? new byte[1] : key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException error) {
            throw new IllegalStateException(error);
        }
    }
}
