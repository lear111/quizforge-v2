package cloud.treenewbee.quizforge.migration;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.bouncycastle.crypto.generators.SCrypt;

/**
 * 离线核对原密码，算法与 V2 的 PasswordHasher、旧平台 AccountCrypto 相同（详细设计 09 第 2.1 节）：
 * scrypt N=16384, r=8, p=1, dkLen=64；salt 是 32 位十六进制文本本身的 UTF-8 字节；
 * 孤立的 UTF-16 代理字符替换为 U+FFFD。
 */
final class LegacyPassword {

    private LegacyPassword() {
    }

    static boolean matches(String plain, String stored) {
        if (plain == null || stored == null || !AccountPlan.PASSWORD_FORMAT.matcher(stored).matches()) return false;
        String[] parts = stored.split(":");
        byte[] actual = SCrypt.generate(utf8(plain), utf8(parts[0]), 16384, 8, 1, 64);
        return MessageDigest.isEqual(actual, HexFormat.of().parseHex(parts[1]));
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith(new byte[] {(byte) 0xef, (byte) 0xbf, (byte) 0xbd})
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalStateException(error);
        }
    }
}
