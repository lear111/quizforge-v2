package cloud.treenewbee.quizforge.ai.web;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.ai.application.AiGateway;
import cloud.treenewbee.quizforge.ai.application.AiGatewaySettings;
import cloud.treenewbee.quizforge.ai.application.UpstreamKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 后台“AI 服务”：上游配置（只读）与 Key 池。/api/admin/** 由 SecurityConfig 统一检查管理员。 */
@RestController
public class AiServiceAdminController {

    public record AddKey(@NotNull @Size(max = 200) String apiKey, @Size(max = 120) String note, @NotNull Long inputPrice,
                         @NotNull Long outputPrice, Instant expiresAt) {
    }

    public record Enabled(@NotNull Boolean enabled) {
    }

    public record Prices(@NotNull Long inputPrice, @NotNull Long outputPrice) {
    }

    public record Order(@NotNull @Size(max = 20) List<String> ids) {
    }

    private final UpstreamKeyService keys;
    private final AiGateway gateway;
    private final AiGatewaySettings settings;

    public AiServiceAdminController(UpstreamKeyService keys, AiGateway gateway, AiGatewaySettings settings) {
        this.keys = keys;
        this.gateway = gateway;
        this.settings = settings;
    }

    @GetMapping("/api/admin/ai-service")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", settings.host());
        config.put("model", settings.model());
        config.put("callTimeoutSeconds", settings.callTimeout().toSeconds());
        config.put("streamTimeoutSeconds", settings.streamTimeout().toSeconds());
        config.put("maxOutputTokens", settings.maxOutputTokens());
        config.put("perUserConcurrency", settings.perUserConcurrency());
        config.put("siteConcurrency", settings.siteConcurrency());
        config.put("maxKeys", AiGatewaySettings.MAX_KEYS);
        config.put("maxKeyAttempts", AiGatewaySettings.MAX_KEY_ATTEMPTS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("config", config, "keys", keys.list()));
    }

    @PostMapping("/api/admin/ai-keys")
    public ResponseEntity<UpstreamKeyService.Item> add(Principal actor, @Valid @RequestBody AddKey body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(keys.add(actor.getName(), body.apiKey(), body.note(),
                body.inputPrice(), body.outputPrice(), body.expiresAt()));
    }

    @PutMapping("/api/admin/ai-keys/{id}/enabled")
    public UpstreamKeyService.Item enabled(Principal actor, @PathVariable String id, @Valid @RequestBody Enabled body) {
        return keys.setEnabled(actor.getName(), id, body.enabled());
    }

    @PutMapping("/api/admin/ai-keys/{id}/prices")
    public UpstreamKeyService.Item prices(Principal actor, @PathVariable String id, @Valid @RequestBody Prices body) {
        return keys.setPrices(actor.getName(), id, body.inputPrice(), body.outputPrice());
    }

    @PutMapping("/api/admin/ai-keys/order")
    public List<UpstreamKeyService.Item> order(Principal actor, @Valid @RequestBody Order body) {
        return keys.reorder(actor.getName(), body.ids());
    }

    @DeleteMapping("/api/admin/ai-keys/{id}")
    public ResponseEntity<Void> delete(Principal actor, @PathVariable String id) {
        keys.delete(actor.getName(), id);
        return ResponseEntity.noContent().build();
    }

    /** 测试连接：一个很短的请求，费用计入管理员自己的 AI 余额。 */
    @PostMapping("/api/admin/ai-keys/{id}/test")
    public Map<String, Object> test(Principal actor, @PathVariable String id) {
        return gateway.testKey(actor.getName(), id);
    }
}
