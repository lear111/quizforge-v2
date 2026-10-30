package cloud.treenewbee.quizforge.practice.application;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Opaque versions, like the legacy host: raw answer-dependent hashes are not public validators. */
@Component
public class QuestionStamps {
    private final byte[] key = new byte[32];
    public QuestionStamps() { new SecureRandom().nextBytes(key); }
    public record Stamp(long revision, String contentVersion, String packageVersion) {}
    public Stamp stamp(String context, String qid, long revision, byte[] content, byte[] extension) {
        return new Stamp(revision, opaque(context + "\0" + qid, content), opaque("package", extension));
    }
    private String opaque(String scope, byte[] hash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(hash));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
