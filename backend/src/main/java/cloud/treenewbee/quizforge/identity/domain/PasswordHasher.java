package cloud.treenewbee.quizforge.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import cloud.treenewbee.quizforge.shared.web.ApiException;

@Component
public class PasswordHasher {

    private static final Pattern STORED_FORMAT = Pattern.compile("[a-f0-9]{32}:[a-f0-9]{128}");
    private static final String DUMMY_STORED = "0".repeat(32) + ":" + "0".repeat(128);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final int PERMITS = 2;
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    // 限制同时进行的哈希计算数量，防止过载（每次约 16 MiB 内存）；超出时排队等待，最长 MAX_WAIT
    private final Semaphore hashing;
    private final Duration maxWait;

    public PasswordHasher() {
        this(PERMITS, MAX_WAIT);
    }

    PasswordHasher(int permits, Duration maxWait) {
        this.hashing = new Semaphore(permits, true);
        this.maxWait = maxWait;
    }

    // 将字符串编码为 UTF-8 字节数组，替换无法编码的字符为 U+FFFD
    private static byte[] utf8(String value) {
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    .replaceWith(new byte[] { (byte) 0xef, (byte) 0xbf, (byte) 0xbd })
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // 生成随机字节数组
    private static byte[] random(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    // 将字节数组转换为十六进制字符串
    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    // 计算 scrypt 哈希值，使用给定的密码和盐值
    private byte[] derive(String password, String salt) {
        if (!acquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_BUSY", "登录服务繁忙，请稍后再试");
        }

        byte[] bytes = utf8(password);
        try {
            return SCrypt.generate(bytes, utf8(salt), 16384, 8, 1, 64);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            hashing.release();
        }
    }

    // 公平排队等待计算名额；等不到（或线程被中断）返回 false
    private boolean acquire() {
        try {
            return hashing.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 测试用：占用或归还计算名额
    Semaphore permits() {
        return hashing;
    }

    // 将密码哈希为带盐的字符串，格式为 "salt:hash"
    public String hash(String password) {

        // 确保密码不为 null
        Objects.requireNonNull(password, "password");
        String salt = hex(random(16));
        return salt + ":" + hex(derive(password, salt));
    }

    // 验证明文密码是否与已编码的哈希值匹配
    public boolean verify(String password, String stored) {
        // 三个条件都满足才算“有资格比对”
        boolean valid = password != null
                && stored != null
                && STORED_FORMAT.matcher(stored).matches();

        // 不合法时，用假的存储值代替，保证后面照样能算
        String[] parts = (valid ? stored : DUMMY_STORED).split(":");

        // 密码为 null 时，用空字符串代替去算，避免空指针
        String candidate = (password == null) ? "" : password;

        byte[] actual = derive(candidate, parts[0]);
        byte[] expected = HexFormat.of().parseHex(parts[1]);

        // 先比较，再看 valid：无论哪种情况都会完整算一遍 scrypt，耗时一致
        return MessageDigest.isEqual(actual, expected) && valid;
    }
}