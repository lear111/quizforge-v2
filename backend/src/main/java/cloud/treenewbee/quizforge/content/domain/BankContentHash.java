package cloud.treenewbee.quizforge.content.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Content identity only; callers supply the resolved extension digest in question order. */
public final class BankContentHash {
    private BankContentHash() {}

    /** Asset keys are paths relative to assets/, without that prefix. Author JSON is not rewritten. */
    public static String compute(Map<String, Object> bank, Map<String, String> assets,
            List<String> questionExtensionDigests) {
        Objects.requireNonNull(bank);
        Objects.requireNonNull(assets);
        Objects.requireNonNull(questionExtensionDigests);
        var inventory = assets.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> List.of(entry.getKey(), entry.getValue())).toList();
        var value = List.of("qf-bank-content-v1", bank, inventory, questionExtensionDigests);
        byte[] bytes = EcmaJson.stringify(value, true).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
