package cloud.treenewbee.quizforge.identity.verification;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 访问外部验证服务的最小 HTTP 客户端：固定 HTTPS 地址、不跟随重定向、不带 Cookie、
 * 总等待 10 秒、响应最多 64 KiB、不自动重试（避免重复计费）。
 */
class ProviderHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE = 64 * 1024;
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    record Signed(URI uri, Map<String, String> headers, String body) {
    }

    JsonNode post(Signed request) {
        var builder = HttpRequest.newBuilder(request.uri()).timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        request.headers().forEach(builder::header);
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length > MAX_RESPONSE) throw new VerificationUnavailableException("Provider response too large");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new VerificationUnavailableException("Provider HTTP " + response.statusCode());
                }
                JsonNode value = JSON.readTree(bytes);
                if (value == null || !value.isObject()) {
                    throw new VerificationUnavailableException("Provider response is not an object");
                }
                return value;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new VerificationUnavailableException("Provider request interrupted", error);
        } catch (VerificationUnavailableException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new VerificationUnavailableException("Provider request failed", error);
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException error) {
            throw new IllegalStateException(error);
        }
    }

    static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
