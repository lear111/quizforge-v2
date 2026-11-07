package cloud.treenewbee.quizforge.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cloud.treenewbee.quizforge.ops.ProductionConfigCheck.Problem;

class ProductionConfigCheckTest {

    private static final String SECRET = "a1b2c3d4".repeat(5);

    private static Map<String, String> complete() {
        Map<String, String> values = new HashMap<>();
        values.put("qf.verification.mode", "provider");
        values.put("qf.security.allowed-origin", "https://quizforge.treenewbee.cloud");
        values.put("spring.datasource.password", SECRET);
        values.put("qf.rule-runner.token", SECRET);
        values.put("qf.verification.email-pepper", SECRET);
        values.put("qf.verification.sms-pepper", SECRET);
        values.put("qf.ai.master-key", Base64.getEncoder().encodeToString(new byte[32]));
        values.put("qf.verification.tencent.enabled", "true");
        values.put("qf.verification.tencent.secret-id", "id-value");
        values.put("qf.verification.tencent.secret-key", "key-value");
        values.put("qf.verification.tencent.from", "QuizForge <noreply@treenewbee.cloud>");
        values.put("qf.verification.tencent.template-id", "12345");
        values.put("qf.verification.aliyun.enabled", "true");
        values.put("qf.verification.aliyun.access-key-id", "id-value");
        values.put("qf.verification.aliyun.access-key-secret", "secret-value");
        values.put("qf.verification.aliyun.sign-name", "签名");
        values.put("qf.verification.aliyun.template-code", "SMS_1");
        values.put("qf.verification.turnstile.site-key", "site-value");
        values.put("qf.verification.turnstile.secret-key", "secret-value");
        return values;
    }

    private static List<Problem> check(Map<String, String> values) {
        return ProductionConfigCheck.problems(values::get);
    }

    private static List<String> names(List<Problem> problems) {
        return problems.stream().map(Problem::variable).toList();
    }

    @Test
    void aCompleteProductionConfigurationPasses() {
        assertThat(check(complete())).isEmpty();
    }

    @Test
    void localDevelopmentWithFakeVerificationIsNotChecked() {
        for (String origin : List.of("http://localhost:8088", "http://127.0.0.1:8099", "https://audio.qf.test")) {
            assertThat(check(Map.of("qf.verification.mode", "fake", "qf.security.allowed-origin", origin))).isEmpty();
        }
    }

    @Test
    void everyMissingValueIsReportedAtOnce() {
        Map<String, String> values = new HashMap<>();
        values.put("qf.verification.mode", "provider");
        values.put("qf.security.allowed-origin", "https://quizforge.treenewbee.cloud");
        assertThat(names(check(values))).containsExactlyInAnyOrder("QF_DB_PASSWORD", "QF_RULE_RUNNER_TOKEN", "QF_EMAIL_CODE_PEPPER",
                "QF_SMS_CODE_PEPPER", "QF_AI_MASTER_KEY", "QF_TENCENT_SES_ENABLED", "QF_TENCENT_SECRET_ID", "QF_TENCENT_SECRET_KEY",
                "QF_TENCENT_SES_FROM", "QF_TENCENT_SES_TEMPLATE_ID", "QF_ALIYUN_SMS_ENABLED", "QF_ALIYUN_SMS_ACCESS_KEY_ID",
                "QF_ALIYUN_SMS_ACCESS_KEY_SECRET", "QF_ALIYUN_SMS_SIGN_NAME", "QF_ALIYUN_SMS_TEMPLATE_CODE", "QF_TURNSTILE_SITE_KEY",
                "QF_TURNSTILE_SECRET_KEY");
    }

    @Test
    void developmentDefaultsAndExampleValuesAreRejected() {
        for (var change : List.of(
                Map.entry("spring.datasource.password", "quizforge"),
                Map.entry("qf.rule-runner.token", "quizforge-local-rule-token"),
                Map.entry("qf.verification.email-pepper", "change-me-change-me-change-me-change-me"),
                Map.entry("qf.verification.sms-pepper", "your-sms-pepper-goes-here-your-sms-pepper"),
                Map.entry("qf.verification.turnstile.secret-key", "<secret>"),
                Map.entry("qf.verification.tencent.from", "noreply@example.com"))) {
            Map<String, String> values = complete();
            values.put(change.getKey(), change.getValue());
            assertThat(check(values)).as(change.getKey()).hasSize(1);
        }
    }

    @Test
    void tooShortSecretsAndInvalidKeysAreRejected() {
        Map<String, String> values = complete();
        values.put("spring.datasource.password", "short");
        values.put("qf.rule-runner.token", "short-token");
        values.put("qf.verification.email-pepper", "short-pepper");
        values.put("qf.ai.master-key", "not base64 !!");
        assertThat(names(check(values))).containsExactlyInAnyOrder("QF_DB_PASSWORD", "QF_RULE_RUNNER_TOKEN", "QF_EMAIL_CODE_PEPPER", "QF_AI_MASTER_KEY");
        values = complete();
        values.put("qf.ai.master-key", Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(names(check(values))).containsExactly("QF_AI_MASTER_KEY");
    }

    @Test
    void aPublicOriginMustBeHttpsAndFakeVerificationIsRefused() {
        for (String origin : List.of("http://quizforge.treenewbee.cloud", "https://quizforge.treenewbee.cloud/app", "not a url")) {
            Map<String, String> values = complete();
            values.put("qf.security.allowed-origin", origin);
            assertThat(names(check(values))).as(origin).containsExactly("QF_PUBLIC_ORIGIN");
        }
        Map<String, String> fake = complete();
        fake.put("qf.verification.mode", "fake");
        assertThat(names(check(fake))).containsExactly("QF_VERIFICATION_MODE");
    }

    @Test
    void reportsNeverContainValues() {
        Map<String, String> values = complete();
        values.put("qf.verification.sms-pepper", "secret-short");
        values.put("qf.ai.master-key", "secret-not-base64");
        assertThat(check(values).toString()).contains("QF_SMS_CODE_PEPPER", "QF_AI_MASTER_KEY").doesNotContain("secret-short", "secret-not-base64");
    }
}
