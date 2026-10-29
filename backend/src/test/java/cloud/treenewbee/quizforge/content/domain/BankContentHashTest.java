package cloud.treenewbee.quizforge.content.domain;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankContentHashTest {
    private static final String DIGEST = "a".repeat(64);
    private static final Map<String, String> ASSETS = Map.of("听力.mp3", "b".repeat(64), "a.png", "c".repeat(64));
    private static final String BANK = """
            {"formatVersion":2,"title":" 作者标题 ","questions":[{"id":"q1","data":{"text":"中文😀","n":1}},{"id":"q2","data":null}]}
            """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bank(String source) {
        return (Map<String, Object>) PackageJson.read(source.getBytes(UTF_8));
    }

    private static String hash(String source, Map<String, String> assets, List<String> extensions) {
        return BankContentHash.compute(bank(source), assets, extensions);
    }

    @Test
    void identicalContentAndReorderedJsonKeysHaveTheSameHash() {
        String expected = hash(BANK, ASSETS, List.of(DIGEST, DIGEST));
        assertThat(hash(BANK, ASSETS, List.of(DIGEST, DIGEST))).isEqualTo(expected);
        String reordered = """
                {"questions":[{"data":{"n":1.0,"text":"中文😀"},"id":"q1"},{"data":null,"id":"q2"}],"title":" 作者标题 ","formatVersion":2}
                """;
        assertThat(hash(reordered, ASSETS, List.of(DIGEST, DIGEST))).isEqualTo(expected);
        assertThat(expected).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"题目", "资源内容", "资源路径", "绑定拓展", "绑定顺序", "题序", "作者元数据"})
    void eachIdentityComponentChangesTheHash(String change) {
        String source = BANK;
        var assets = new LinkedHashMap<>(ASSETS);
        List<String> digests = List.of(DIGEST, "d".repeat(64));
        String original = hash(source, assets, digests);
        switch (change) {
            case "题目" -> source = source.replace("中文😀", "新内容");
            case "资源内容" -> assets.put("a.png", "e".repeat(64));
            case "资源路径" -> assets.put("renamed.png", assets.remove("a.png"));
            case "绑定拓展" -> digests = List.of(DIGEST, "e".repeat(64));
            case "绑定顺序" -> digests = List.of("d".repeat(64), DIGEST);
            case "题序" -> source = """
                    {"formatVersion":2,"title":" 作者标题 ","questions":[{"id":"q2","data":null},{"id":"q1","data":{"text":"中文😀","n":1}}]}
                    """;
            case "作者元数据" -> source = source.replace(" 作者标题 ", "作者标题");
            default -> throw new AssertionError(change);
        }
        assertThat(hash(source, assets, digests)).isNotEqualTo(original);
    }

    @Test
    void exactVersionedUtf8PreimageAndSortedAssetPaths() throws Exception {
        String expectedPreimage = """
                ["qf-bank-content-v1",{"formatVersion":2,"questions":[{"data":{"n":1,"text":"中文😀"},"id":"q1"},{"data":null,"id":"q2"}],"title":" 作者标题 "},[["a.png","%s"],["听力.mp3","%s"]],["%s","%s"]]
                """.formatted("c".repeat(64), "b".repeat(64), DIGEST, DIGEST).stripTrailing();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedPreimage.getBytes(UTF_8)));
        assertThat(hash(BANK, ASSETS, List.of(DIGEST, DIGEST))).isEqualTo(expected);
        var reversedAssets = new LinkedHashMap<String, String>();
        reversedAssets.put("听力.mp3", "b".repeat(64)); reversedAssets.put("a.png", "c".repeat(64));
        assertThat(hash(BANK, reversedAssets, List.of(DIGEST, DIGEST))).isEqualTo(expected);
    }

    @Test
    void doesNotNormalizeAuthorFieldsOrMutateInputAndSupportsNoAssets() {
        Map<String, Object> original = bank(BANK);
        String before = EcmaJson.stringify(original, false);
        String hash = BankContentHash.compute(original, Map.of(), List.of(DIGEST, DIGEST));
        assertThat(EcmaJson.stringify(original, false)).isEqualTo(before);
        assertThat(hash).isNotEqualTo(hash(BANK.replace("\"title\":\" 作者标题 ", "\"title\":\"作者标题"), Map.of(), List.of(DIGEST, DIGEST)));
        assertThat(hash("{\"questions\":[]}", Map.of(), List.of())).matches("[a-f0-9]{64}");
    }
}
