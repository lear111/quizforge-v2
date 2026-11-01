package cloud.treenewbee.quizforge.identity.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import cloud.treenewbee.quizforge.identity.domain.Contact;

/**
 * 服务商请求签名的离线测试，不访问网络。期望值由按官方签名规范独立编写的 Python 程序计算
 * （见 PR 说明），用于发现 Java 实现中的拼接错误；真实服务商的收发仍需上线前人工验收。
 */
class ProviderSigningTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void tencentSesUsesTc3Signature() {
        var config = new VerificationProperties.Tencent(true, "AKIDTEST", "test-secret-key", "noreply@example.test",
                "12345", "ap-hongkong");
        var sender = new TencentSesEmailSender(config, new ProviderHttp(), CLOCK);

        var signed = sender.sign("{\"a\":1}", 1761984000L);

        assertThat(signed.uri().toString()).isEqualTo("https://ses.tencentcloudapi.com/");
        assertThat(signed.headers()).containsEntry("X-TC-Action", "SendEmail")
                .containsEntry("X-TC-Version", "2020-10-02")
                .containsEntry("X-TC-Region", "ap-hongkong")
                .containsEntry("X-TC-Timestamp", "1761984000");
        assertThat(signed.headers().get("Authorization")).isEqualTo("TC3-HMAC-SHA256 "
                + "Credential=AKIDTEST/2025-11-01/ses/tc3_request, SignedHeaders=content-type;host;x-tc-action, "
                + "Signature=ca0b2cc73535547e3a33b412a8552e6b9d0187ee91c8b229f8e7c70a99b8723d");
        assertThat(sender.configured()).isTrue();
    }

    @Test
    void tencentRequestCarriesTemplateCodeAndPurposeSubject() {
        var config = new VerificationProperties.Tencent(true, "AKIDTEST", "test-secret-key", "noreply@example.test",
                "12345", "ap-guangzhou");
        var signed = new TencentSesEmailSender(config, new ProviderHttp(), CLOCK)
                .request("user@example.test", "654321", VerificationPurpose.RESET_PASSWORD, CLOCK.instant());

        assertThat(signed.body()).contains("\"Destination\":[\"user@example.test\"]")
                .contains("QuizForge 找回密码验证码")
                .contains("\"TemplateID\":12345")
                .contains("654321");
    }

    @Test
    void aliyunSmsUsesAcs3SignatureAndDisablesProviderRetries() {
        var config = new VerificationProperties.Aliyun(true, "LTAITEST", "test-access-secret", "速通互联验证码", "100001");
        var sender = new AliyunSmsSender(config, new ProviderHttp(), CLOCK);

        var signed = sender.request("+8613800138000", "123456", "out-1", CLOCK.instant(), "nonce-1");

        assertThat(signed.uri().getRawQuery()).startsWith("AutoRetry=0&CountryCode=86&Interval=60&OutId=out-1"
                + "&PhoneNumber=13800138000&ReturnVerifyCode=false");
        assertThat(signed.headers().get("Authorization")).isEqualTo("ACS3-HMAC-SHA256 Credential=LTAITEST,"
                + "SignedHeaders=host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version,"
                + "Signature=39db420c7c9f64ed8b532d710474f16745094d2d31f56a82c3d265d35d967864");
        assertThat(signed.headers()).doesNotContainKey("host");
    }

    @Test
    void unconfiguredProvidersAreUnavailableAndTestTurnstileKeysAreRejected() {
        var properties = new VerificationProperties("provider", "short", "", 100, 100,
                new VerificationProperties.Tencent(true, "id", "key", "from@example.test", "1", "ap-hongkong"),
                null, new VerificationProperties.Turnstile("1x00000000000000000000AA", "1x0000000000000000000000000000000AA"));
        var provider = new ProviderVerification(properties, CLOCK);

        // 邮件服务已配置，但摘要密钥不足 32 个字符时仍不可用。
        assertThat(provider.available(Contact.Channel.EMAIL)).isFalse();
        assertThat(provider.available(Contact.Channel.PHONE)).isFalse();
        assertThat(provider.ready()).isFalse();
        assertThat(provider.siteKey()).isEmpty();
        assertThat(provider.verify("token", "127.0.0.1", "example.test", "login_email")).isFalse();
    }

    @Test
    void fakeModeIsOnlyAllowedForLocalOrTestOrigins() {
        assertThat(VerificationConfig.localOrTestOrigin("http://localhost:8088")).isTrue();
        assertThat(VerificationConfig.localOrTestOrigin("https://quiz.example.test")).isTrue();
        assertThat(VerificationConfig.localOrTestOrigin("https://quizforge.example.com")).isFalse();
        assertThat(VerificationConfig.localOrTestOrigin("https://localhost.example.com")).isFalse();
    }
}
