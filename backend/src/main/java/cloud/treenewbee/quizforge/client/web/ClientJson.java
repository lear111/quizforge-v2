package cloud.treenewbee.quizforge.client.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;

import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** 客户端接口的 JSON 请求体：必须是 application/json，有大小上限，拒绝重复键（与旧平台 AccountHttp 相同）。 */
final class ClientJson {

    static final int MAX_BYTES = 24_000;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private ClientJson() {
    }

    static boolean isJson(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).matches("application/json(?:;.*)?");
    }

    /** 读取设备接口的小请求体。 */
    static Map<String, Object> body(HttpServletRequest request) {
        if (!isJson(request)) throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请使用 JSON 请求");
        if (request.getContentLengthLong() > MAX_BYTES) throw rejected(HttpStatus.CONTENT_TOO_LARGE, "上传内容超过大小限制");
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw rejected(HttpStatus.CONTENT_TOO_LARGE, "上传内容超过大小限制");
            return object(bytes);
        } catch (IOException | IllegalArgumentException | JacksonException error) {
            throw rejected(HttpStatus.BAD_REQUEST, "请求内容格式错误");
        }
    }

    /** 解析为对象；不是对象、语法错误或有重复键时抛出 IllegalArgumentException 或 JacksonException。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(byte[] bytes) {
        Object value = JSON.readValue(bytes, Object.class);
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("请求内容应为对象");
        return (Map<String, Object>) map;
    }

    private static ApiException rejected(HttpStatus status, String message) {
        return new ApiException(status, "CONNECT_REQUEST_REJECTED", message);
    }
}
