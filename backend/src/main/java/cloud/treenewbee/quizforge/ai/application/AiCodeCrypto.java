package cloud.treenewbee.quizforge.ai.application;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 兑换码完整码的加密保存（AES-256-GCM，附加数据区分用途）。密钥来自环境变量 QF_AI_MASTER_KEY
 * （32 字节的 Base64）。provider 模式或站点来源不是本机 / .test 时缺少密钥，启动失败；
 * 只在本机开发与测试中使用由固定字符串派生的开发密钥。
 *
 * <p>存储格式：Base64(12 字节 IV ‖ 密文 ‖ 16 字节 GCM 标签)。
 */
@Component
public class AiCodeCrypto {

    static final String CODE_AAD = "quizforge-redeem-code-v1";
    private static final Logger log = LoggerFactory.getLogger(AiCodeCrypto.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public AiCodeCrypto(@Value("${qf.ai.master-key:}") String masterKey,
                        @Value("${qf.verification.mode:provider}") String mode,
                        @Value("${qf.security.allowed-origin}") String origin) {
        byte[] raw = decode(masterKey);
        if (raw == null) {
            if (!"fake".equals(mode) || !localOrTestOrigin(origin)) {
                throw new IllegalStateException("QF_AI_MASTER_KEY must be a Base64 encoded 32-byte key "
                        + "(required unless running locally with qf.verification.mode=fake)");
            }
            log.warn("兑换码使用开发密钥加密：只用于本机开发与测试");
            raw = sha256("quizforge-development-ai-master-key");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(CODE_AAD.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Redeem code encryption failed", error);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] bytes = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(CODE_AAD.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("Redeem code decryption failed", error);
        }
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(value.strip());
            if (raw.length != 32) throw new IllegalStateException("QF_AI_MASTER_KEY must decode to 32 bytes");
            return raw;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("QF_AI_MASTER_KEY is not valid Base64", error);
        }
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    static boolean localOrTestOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.endsWith(".localhost") || host.endsWith(".test"));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
