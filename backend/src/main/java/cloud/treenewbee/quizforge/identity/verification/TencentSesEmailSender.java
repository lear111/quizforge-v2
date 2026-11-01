package cloud.treenewbee.quizforge.identity.verification;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * 腾讯云 SES 模板邮件（API 3.0，TC3-HMAC-SHA256 签名，接口版本 2020-10-02）。
 * 沿用旧平台的配置项：SecretId/SecretKey、发件地址、模板 ID（模板变量 {@code code}）、地域限香港或广州。
 */
class TencentSesEmailSender {

    private static final String HOST = "ses.tencentcloudapi.com";
    private static final Set<String> REGIONS = Set.of("ap-hongkong", "ap-guangzhou");

    private final VerificationProperties.Tencent config;
    private final ProviderHttp http;
    private final Clock clock;

    TencentSesEmailSender(VerificationProperties.Tencent config, ProviderHttp http, Clock clock) {
        this.config = config;
        this.http = http;
        this.clock = clock;
    }

    boolean configured() {
        return config.enabled() && !config.secretId().isEmpty() && !config.secretKey().isEmpty()
                && !config.from().isEmpty() && REGIONS.contains(config.region()) && templateId() > 0;
    }

    void send(String email, String code, VerificationPurpose purpose) {
        if (!configured()) throw new VerificationUnavailableException("Email provider not configured");
        JsonNode result = http.post(request(email, code, purpose, clock.instant()));
        JsonNode response = result.get("Response");
        if (response == null || response.has("Error") || response.path("MessageId").asString("").isEmpty()) {
            throw new VerificationUnavailableException("Email provider rejected the message");
        }
    }

    ProviderHttp.Signed request(String email, String code, VerificationPurpose purpose, Instant now) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("TemplateID", templateId());
        template.put("TemplateData", ProviderHttp.JSON.writeValueAsString(Map.of("code", code)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("FromEmailAddress", "QuizForge <" + config.from() + ">");
        payload.put("Destination", List.of(email));
        payload.put("Subject", "QuizForge " + purpose.label() + "验证码");
        payload.put("Template", template);
        payload.put("TriggerType", 1);
        return sign(ProviderHttp.JSON.writeValueAsString(payload), now.getEpochSecond());
    }

    /** TC3-HMAC-SHA256：规范请求 → 待签字符串 → 派生密钥 → 签名。 */
    ProviderHttp.Signed sign(String body, long timestamp) {
        String date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString();
        String contentType = "application/json; charset=utf-8";
        String signedHeaders = "content-type;host;x-tc-action";
        String canonical = "POST\n/\n\n"
                + "content-type:" + contentType + "\n"
                + "host:" + HOST + "\n"
                + "x-tc-action:sendemail\n\n"
                + signedHeaders + "\n"
                + ProviderHttp.sha256Hex(body);
        String scope = date + "/ses/tc3_request";
        String toSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + ProviderHttp.sha256Hex(canonical);
        byte[] secretDate = ProviderHttp.hmac(("TC3" + config.secretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = ProviderHttp.hmac(secretDate, "ses");
        byte[] secretSigning = ProviderHttp.hmac(secretService, "tc3_request");
        String signature = ProviderHttp.hex(ProviderHttp.hmac(secretSigning, toSign));
        String authorization = "TC3-HMAC-SHA256 Credential=" + config.secretId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Authorization", authorization);
        headers.put("X-TC-Action", "SendEmail");
        headers.put("X-TC-Version", "2020-10-02");
        headers.put("X-TC-Region", config.region());
        headers.put("X-TC-Timestamp", String.valueOf(timestamp));
        return new ProviderHttp.Signed(URI.create("https://" + HOST + "/"), headers, body);
    }

    private long templateId() {
        try {
            return Long.parseLong(config.templateId());
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
