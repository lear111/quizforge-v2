package cloud.treenewbee.quizforge.content.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 规则计算只依赖题目 data 与未作答状态；与包含资源、大纲的持久化题目指纹分开。 */
public final class RuleInputHash {
    private RuleInputHash() {}

    public static String compute(Object data) {
        return hash(java.util.Arrays.asList("qf-import-validation-v1", data));
    }

    public static String capabilities() { return hash(List.of("qf-import-capabilities-v1")); }

    private static String hash(Object value) {
        byte[] input = EcmaJson.stringify(value, true)
                .getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
