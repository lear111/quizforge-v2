package cloud.treenewbee.quizforge.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SessionToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private SessionToken() {
    }

    /** 生成新令牌：放进 Cookie 发给浏览器 */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 判断浏览器传来的值格式是否正确 */
    public static boolean isWellFormed(String raw) {
        return raw != null && TOKEN_PATTERN.matcher(raw).matches();
    }

    /** 计算令牌的 SHA-256：存进数据库，查询时也用它，调用前应先用 isWellFormed 校验 */
    public static byte[] hash(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(raw.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}