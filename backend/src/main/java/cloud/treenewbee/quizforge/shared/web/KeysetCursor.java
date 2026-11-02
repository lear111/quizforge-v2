package cloud.treenewbee.quizforge.shared.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * 键集分页的游标：上一页最后一行的 (时间, ID)，编码为不透明的 base64url 字符串。
 * 列表按 (时间, ID) 倒序，下一页取严格小于游标的行；不使用 OFFSET。
 */
public record KeysetCursor(Instant time, String id) {

    private static final Pattern ID = Pattern.compile("[0-9a-f-]{36}");

    public static String encode(Instant time, String id) {
        String raw = time.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** 空字符串或 null 表示第一页；格式错误返回 400。 */
    public static KeysetCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int colon = raw.indexOf(':');
            String id = raw.substring(colon + 1);
            if (colon < 1 || !ID.matcher(id).matches()) throw new IllegalArgumentException();
            return new KeysetCursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, colon))), id);
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
        }
    }
}
