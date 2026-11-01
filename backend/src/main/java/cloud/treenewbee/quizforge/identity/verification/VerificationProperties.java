package cloud.treenewbee.quizforge.identity.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 验证码与人机验证服务的配置，全部来自环境变量（见 application.yml），密钥不进入仓库。
 *
 * <p>{@code mode=fake} 只用于本机开发与测试：验证码写入日志和 {@link FakeVerification} 的内存记录，
 * 人机验证接受固定令牌。{@code mode=provider} 对接旧平台使用的服务商：腾讯云 SES（邮件）、
 * 阿里云号码认证短信（PNVS）、Cloudflare Turnstile。
 */
@ConfigurationProperties("qf.verification")
public record VerificationProperties(
        String mode,
        String emailPepper,
        String smsPepper,
        int smsDailyLimit,
        int emailDailyLimit,
        int ipLimitTenMinutes,
        int ipLimitDaily,
        Tencent tencent,
        Aliyun aliyun,
        Turnstile turnstile) {

    public static final int MIN_PEPPER_LENGTH = 32;

    public VerificationProperties {
        mode = mode == null || mode.isBlank() ? "provider" : mode.trim();
        if (!mode.equals("provider") && !mode.equals("fake")) {
            throw new IllegalArgumentException("qf.verification.mode must be provider or fake");
        }
        emailPepper = emailPepper == null ? "" : emailPepper;
        smsPepper = smsPepper == null ? "" : smsPepper;
        if (smsDailyLimit < 1 || smsDailyLimit > 10000) throw new IllegalArgumentException("Invalid SMS daily limit");
        if (emailDailyLimit < 1 || emailDailyLimit > 10000) throw new IllegalArgumentException("Invalid email daily limit");
        if (ipLimitTenMinutes < 1 || ipLimitDaily < ipLimitTenMinutes) {
            throw new IllegalArgumentException("Invalid verification IP limits");
        }
        tencent = tencent == null ? new Tencent(false, "", "", "", "", "ap-hongkong") : tencent;
        aliyun = aliyun == null ? new Aliyun(false, "", "", "", "") : aliyun;
        turnstile = turnstile == null ? new Turnstile("", "") : turnstile;
    }

    public boolean fake() {
        return mode.equals("fake");
    }

    public record Tencent(boolean enabled, String secretId, String secretKey, String from, String templateId,
                          String region) {
        public Tencent {
            secretId = blank(secretId);
            secretKey = blank(secretKey);
            from = blank(from);
            templateId = blank(templateId);
            region = region == null || region.isBlank() ? "ap-hongkong" : region.trim();
        }
    }

    public record Aliyun(boolean enabled, String accessKeyId, String accessKeySecret, String signName,
                         String templateCode) {
        public Aliyun {
            accessKeyId = blank(accessKeyId);
            accessKeySecret = blank(accessKeySecret);
            signName = blank(signName);
            templateCode = blank(templateCode);
        }
    }

    public record Turnstile(String siteKey, String secretKey) {
        public Turnstile {
            siteKey = blank(siteKey);
            secretKey = blank(secretKey);
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
