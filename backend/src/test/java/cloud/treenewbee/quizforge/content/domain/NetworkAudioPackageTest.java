package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.*;
import static cloud.treenewbee.quizforge.content.domain.PackageFixture.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NetworkAudioPackageTest {
    static final String URL = "https://media.example.com/a%20b.mp3?x=1%2f2";

    @Test void mixedLocalAndNetworkMaterialsKeepAuthorBytesAndDoNotStoreRemoteAudio() throws Exception {
        var f = new PackageFixture("单选题");
        byte[] mp3 = new byte[] {73, 68, 51, 4};
        f.files.put(BANK + "/assets/a.mp3", mp3);
        var config = Map.of("materials", List.of(Map.of("id", "local", "title", "本地", "file", "assets/a.mp3"),
                Map.of("id", "network", "title", "网络", "url", URL)), "examMaterialId", "network");
        f.bank(bank -> bank.put("listening", config));
        f.question(q -> q.put("listening", Map.of("materialId", "network")));
        var bank = f.parse().banks().getFirst();
        assertThat(bank.bank().get("listening")).isEqualTo(config);
        assertThat(bank.assets()).containsKey("assets/a.mp3").hasSize(2);
        assertThat(f.stored.values()).noneMatch(bytes -> java.util.Arrays.equals(bytes, URL.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void changingOnlyUrlChangesContentIdentityWithoutNormalizingAuthorValue() throws Exception {
        var f = new PackageFixture("单选题");
        f.bank(b -> b.put("listening", Map.of("materials", List.of(Map.of("id", "network", "title", "网络", "url", URL)))));
        var original = f.parse().banks().getFirst().bank();
        String hash = BankContentHash.compute(original, Map.of(), List.of("aa".repeat(32)));
        f.bank(b -> b.put("listening", Map.of("materials", List.of(Map.of("id", "network", "title", "网络", "url", URL + "&v=2")))));
        assertThat(BankContentHash.compute(f.parse().banks().getFirst().bank(), Map.of(), List.of("aa".repeat(32)))).isNotEqualTo(hash);
    }

    @ParameterizedTest @ValueSource(strings = {"both", "neither", "null", "unsafe"})
    void rejectedMaterialReportsIdAndExactField(String kind) throws Exception {
        var f = new PackageFixture("单选题");
        var material = new LinkedHashMap<String, Object>(Map.of("id", "section-a", "title", "材料"));
        if (kind.equals("both")) { material.put("url", URL); material.put("file", "assets/a.mp3"); }
        if (kind.equals("null")) material.put("url", null);
        if (kind.equals("unsafe")) material.put("url", "https://127.0.0.1/a.mp3");
        f.bank(b -> b.put("listening", Map.of("materials", List.of(material))));
        assertThatThrownBy(f::parse).isInstanceOfSatisfying(PackageRejectedException.class, e -> {
            assertThat(e.code()).isEqualTo(PackageRejectedException.Code.INVALID_QUESTION_BANK);
            assertThat(e.getMessage()).contains("listening.materials[0]", "section-a", "url");
        });
    }
}
