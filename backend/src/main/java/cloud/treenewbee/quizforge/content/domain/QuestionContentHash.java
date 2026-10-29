package cloud.treenewbee.quizforge.content.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/** A conservative question version includes its effective outline, listening materials and resources. */
public final class QuestionContentHash {
    private QuestionContentHash() {}

    public static String compute(Map<String, Object> question, String extensionDigest, Object outline,
            Object bankListening, Map<String, String> assets) {
        var inventory = assets.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> java.util.List.of(entry.getKey(), entry.getValue())).toList();
        byte[] bytes = EcmaJson.stringify(Arrays.asList("qf-question-content-v1", question, extensionDigest,
                outline, bankListening, inventory), true).getBytes(StandardCharsets.UTF_8);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
