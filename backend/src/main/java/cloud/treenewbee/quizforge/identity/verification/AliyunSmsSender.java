package cloud.treenewbee.quizforge.identity.verification;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

/**
 * 阿里云号码认证服务的短信验证码（SendSmsVerifyCode，2017-05-25，ACS3-HMAC-SHA256 签名）。
 *
 * <p>与旧平台一致：验证码由本服务生成，经 TemplateParam 交给阿里云发送（模板变量 code、min）；
 * 不使用阿里云的校验接口，禁用接口自动补发。
 */
class AliyunSmsSender {

    private static final String HOST = "dypnsapi.aliyuncs.com";

    private final VerificationProperties.Aliyun config;
    private final ProviderHttp http;
    private final Clock clock;

    AliyunSmsSender(VerificationProperties.Aliyun config, ProviderHttp http, Clock clock) {
        this.config = config;
        this.http = http;
        this.clock = clock;
    }

    boolean configured() {
        return config.enabled() && !config.accessKeyId().isEmpty() && !config.accessKeySecret().isEmpty()
                && !config.signName().isEmpty() && !config.templateCode().isEmpty();
    }

    void send(String phone, String code, String outId) {
        if (!configured()) throw new VerificationUnavailableException("SMS provider not configured");
        JsonNode result = http.post(request(phone, code, outId, clock.instant(), UUID.randomUUID().toString()));
        if (!"OK".equals(result.path("Code").asString("")) || !result.path("Success").asBoolean(false)) {
            throw new VerificationUnavailableException("SMS provider rejected the message");
        }
    }

    ProviderHttp.Signed request(String phone, String code, String outId, Instant now, String nonce) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("PhoneNumber", phone.substring(3));
        params.put("CountryCode", "86");
        params.put("SignName", config.signName());
        params.put("TemplateCode", config.templateCode());
        params.put("TemplateParam", ProviderHttp.JSON.writeValueAsString(new TreeMap<>(Map.of("code", code, "min", "10"))));
        params.put("OutId", outId);
        params.put("ValidTime", "600");
        params.put("Interval", "60");
        params.put("ReturnVerifyCode", "false");
        params.put("AutoRetry", "0");
        String query = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        TreeMap<String, String> signed = new TreeMap<>();
        signed.put("host", HOST);
        signed.put("x-acs-action", "SendSmsVerifyCode");
        signed.put("x-acs-content-sha256", ProviderHttp.sha256Hex(""));
        signed.put("x-acs-date", now.truncatedTo(ChronoUnit.SECONDS).toString());
        signed.put("x-acs-signature-nonce", nonce);
        signed.put("x-acs-version", "2017-05-25");
        String signedHeaders = String.join(";", signed.keySet());
        String canonicalHeaders = signed.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().strip() + "\n")
                .collect(Collectors.joining());
        String canonical = "POST\n/\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + ProviderHttp.sha256Hex("");
        String signature = ProviderHttp.hex(ProviderHttp.hmac(
                config.accessKeySecret().getBytes(StandardCharsets.UTF_8),
                "ACS3-HMAC-SHA256\n" + ProviderHttp.sha256Hex(canonical)));
        Map<String, String> headers = new LinkedHashMap<>();
        signed.forEach((name, value) -> {
            if (!name.equals("host")) headers.put(name, value);
        });
        headers.put("Accept", "application/json");
        headers.put("Authorization", "ACS3-HMAC-SHA256 Credential=" + config.accessKeyId()
                + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature);
        return new ProviderHttp.Signed(URI.create("https://" + HOST + "/?" + query), headers, "");
    }

    /** RFC 3986 百分号编码（阿里云 V3 签名要求）。 */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
