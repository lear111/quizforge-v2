package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExtensionDigestTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<Arguments> vectors() throws Exception {
        try (InputStream input = ExtensionDigestTest.class.getResourceAsStream("/content/extension-digest-vectors.json")) {
            JsonNode root = JSON.readTree(input);
            assertThat(root.get("legacyCommit").asString()).isEqualTo("83d6dfd");
            return root.get("cases").valueStream().map(value -> Arguments.of(value.get("name").asString(), value));
        }
    }

    private static Map<String, byte[]> files(JsonNode vector) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        vector.get("files").forEach(file -> result.put(file.get("path").asString(), Base64.getDecoder().decode(file.get("base64").asString())));
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void matchesLegacyDigestAndEveryInventoryField(String name, JsonNode vector) {
        Map<String, byte[]> files = files(vector);
        var actual = ExtensionDigest.inspect(files);
        assertThat(actual.digest()).isEqualTo(vector.get("expected").get("digest").asString());
        JsonNode expectedFiles = vector.get("expected").get("files");
        assertThat(actual.files()).hasSize(expectedFiles.size());
        for (int index = 0; index < actual.files().size(); index++) {
            var actualFile = actual.files().get(index);
            var expectedFile = expectedFiles.get(index);
            assertThat(actualFile.path()).isEqualTo(expectedFile.get("path").asString());
            assertThat(actualFile.size()).isEqualTo(expectedFile.get("size").asLong());
            assertThat(actualFile.sha256()).isEqualTo(expectedFile.get("sha256").asString());
        }
        assertThat(ExtensionDigest.compute(files)).isEqualTo(actual.digest());
    }

    @Test
    void displayNameJsonWhitespaceAndBindingChangesDoNotChangeIdentity() throws Exception {
        JsonNode vector = (JsonNode) vectors().findFirst().orElseThrow().get()[1];
        Map<String, byte[]> files = files(vector);
        String original = ExtensionDigest.compute(files);
        @SuppressWarnings("unchecked") Map<String, Object> manifest = (Map<String, Object>) EcmaJson.parse(files.get("manifest.json"));
        manifest.put("name", "新的显示名😀");
        files.put("manifest.json", (" \n" + EcmaJson.stringify(manifest, false) + "\n").getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked") Map<String, Object> bank = (Map<String, Object>) EcmaJson.parse(files.get("examples/bank.json"));
        bank.put("extension", Map.of("path", "extensions/新位置/单选题"));
        files.put("examples/bank.json", EcmaJson.stringify(bank, false).getBytes(StandardCharsets.UTF_8));
        assertThat(ExtensionDigest.compute(files)).isEqualTo(original);
        files.put("rules.js", "changed executable bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(ExtensionDigest.compute(files)).isNotEqualTo(original);
    }

    @Test
    void rejectsUnsafePathsMissingManifestAndInvalidExamples() {
        for (String path : new String[] {"../a", "/a", "a//b", "a/./b", "a\\b"}) {
            assertThatThrownBy(() -> ExtensionDigest.compute(Map.of(path, new byte[0]))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ExtensionDigest.compute(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtensionDigest.compute(Map.of("manifest.json", "[]".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(IllegalArgumentException.class);
        for (String examples : new String[] {"bank.json", "examples/../bank.json", "examples/a.txt"}) {
            byte[] manifest = ("{\"examples\":\"" + examples + "\"}").getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ExtensionDigest.compute(Map.of("manifest.json", manifest))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ExtensionDigest.compute(Map.of("a", new byte[0], "a/b", new byte[0]))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFileCountIndividualAndTotalByteLimits() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int index = 0; index < 2001; index++) files.put("file-" + index, new byte[0]);
        assertThatThrownBy(() -> ExtensionDigest.compute(files)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtensionDigest.compute(Map.of("big", new byte[8 * 1024 * 1024 + 1]))).isInstanceOf(IllegalArgumentException.class);
        files.clear();
        byte[] block = new byte[8 * 1024 * 1024];
        for (int index = 0; index < 9; index++) files.put("file-" + index, block);
        assertThatThrownBy(() -> ExtensionDigest.compute(files)).isInstanceOf(IllegalArgumentException.class);
    }
}
