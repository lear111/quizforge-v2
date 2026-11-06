package cloud.treenewbee.quizforge.client.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.client.application.ClientAiService;
import cloud.treenewbee.quizforge.client.application.DeviceService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;

/** AI 网关（遗留契约 C-05 第 9 节）：只接受设备令牌，浏览器 Cookie 无效。 */
@RestController
public class ClientAiController {

    /** 与旧平台相同：请求体上限 13 MiB，其中图片合计 12 MiB。 */
    static final int MAX_BODY_BYTES = 13 * 1024 * 1024;

    private final DeviceService devices;
    private final ClientAiService ai;

    public ClientAiController(DeviceService devices, ClientAiService ai) {
        this.devices = devices;
        this.ai = ai;
    }

    @PostMapping("/api/ai/v1/chat/completions")
    public ResponseEntity<Map<String, Object>> completion(HttpServletRequest request) {
        var device = devices.device(request.getHeader("Authorization"));
        if (!ClientJson.isJson(request)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AI_REQUEST_UNSUPPORTED", "请使用 JSON 请求");
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        Map<String, Object> body;
        try {
            byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (raw.length > MAX_BODY_BYTES) throw tooLarge();
            body = ClientJson.object(raw);
        } catch (IOException | IllegalArgumentException | JacksonException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_REQUEST_UNSUPPORTED", "请求内容不是有效的 JSON");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ai.complete(device.userId(), body));
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "AI_REQUEST_TOO_LARGE", "请求超过 12 MiB 上限");
    }
}
