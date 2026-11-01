package cloud.treenewbee.quizforge.practice.resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;

@Service
public class ImageUploads {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4;
    public static final int MAX_BODY_BYTES = MAX_BASE64 + 4096;
    public record Uploaded(String id, String mime, int size) {}
    private final BlobStore blobs;
    private final UserResourceMapper resources;
    private final UploadLimiter limiter;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ImageUploads(BlobStore blobs, UserResourceMapper resources, UploadLimiter limiter, Clock clock, TransactionTemplate tx) {
        this.blobs = blobs; this.resources = resources; this.limiter = limiter; this.clock = clock; this.tx = tx;
    }

    public Uploaded upload(String userId, JsonNode body, String transientFlag) throws IOException {
        if (transientFlag != null && !"true".equals(transientFlag)) throw invalid("临时资源标记无效");
        if (body == null || !body.isObject() || !body.path("data").isString()) throw invalid("图片需要 base64 data 字段");
        byte[] bytes = decode(body.path("data").asString());
        String mime = detect(bytes); // A claimed MIME never authorizes executable or mismatched content.
        limiter.acquire(userId);
        String sha = blobs.put(bytes);
        // V2 always retains history: the legacy transient hint does not make answer images ephemeral.
        tx.executeWithoutResult(status -> resources.insertIfAbsent(new UserResourceRow(userId,
                HexFormat.of().parseHex(sha), mime, bytes.length, clock.instant().truncatedTo(ChronoUnit.MILLIS))));
        return new Uploaded(sha, mime, bytes.length);
    }

    static byte[] decode(String encoded) {
        if (encoded.length() > MAX_BASE64) throw tooLarge();
        if (encoded.isEmpty()) throw invalid("图片内容不能为空");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException malformed) { throw invalid("图片 base64 格式无效"); }
        if (bytes.length > MAX_BYTES) throw tooLarge();
        return bytes;
    }

    static String detect(byte[] bytes) {
        if (bytes.length >= 24 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})
                && ascii(bytes, 12, 4).equals("IHDR")) return "image/png";
        if (bytes.length >= 4 && bytes[0] == (byte)255 && bytes[1] == (byte)216
                && bytes[bytes.length-2] == (byte)255 && bytes[bytes.length-1] == (byte)217) return "image/jpeg";
        if (bytes.length >= 16 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")
                && java.util.Set.of("VP8 ", "VP8L", "VP8X").contains(ascii(bytes, 12, 4))) return "image/webp";
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_UNSUPPORTED", "只支持 PNG、JPEG、WebP 图片");
    }
    private static String ascii(byte[] bytes, int offset, int length) { return new String(bytes, offset, length, StandardCharsets.US_ASCII); }
    public static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_SIZE_LIMIT", "图片超过 4 MiB 限制"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message); }
}
