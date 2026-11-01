package cloud.treenewbee.quizforge.identity.verification;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** Cloudflare Turnstile 服务端校验，核对 success、hostname 与 action。 */
class TurnstileVerifier {

    private static final URI SITEVERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final VerificationProperties.Turnstile config;
    private final ProviderHttp http;

    TurnstileVerifier(VerificationProperties.Turnstile config, ProviderHttp http) {
        this.config = config;
        this.http = http;
    }

    /** Cloudflare 公布的测试密钥以 1x/2x/3x 开头，不视为已配置（与旧平台一致）。 */
    boolean configured() {
        return production(config.siteKey()) && production(config.secretKey());
    }

    boolean verify(String token, String remoteIp, String hostname, String action) {
        if (!configured() || token == null || token.isEmpty() || token.length() > 2048) return false;
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("secret", config.secretKey());
            body.put("response", token);
            if (remoteIp != null) body.put("remoteip", remoteIp);
            JsonNode result = http.post(new ProviderHttp.Signed(SITEVERIFY,
                    Map.of("Content-Type", "application/json"), ProviderHttp.JSON.writeValueAsString(body)));
            return result.path("success").asBoolean(false)
                    && hostname.equals(result.path("hostname").asString(""))
                    && action.equals(result.path("action").asString(""));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean production(String value) {
        return !value.isEmpty() && !value.matches("^(?:1x|2x|3x).*");
    }
}
