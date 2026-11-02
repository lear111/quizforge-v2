package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class NetworkAudioUrlTest {
    static Stream<Arguments> vectors() throws Exception {
        var file = Path.of(System.getProperty("basedir", ".")).resolve("../frontend/test/fixtures/network-audio-urls.json");
        return JsonMapper.builder().build().readTree(java.nio.file.Files.readAllBytes(file)).valueStream()
                .map(row -> Arguments.of(row.get("url").isString() ? row.get("url").asString() : null, row.path("valid").asBoolean()));
    }
    @ParameterizedTest @MethodSource("vectors")
    void matchesBrowserAddressRules(String url, boolean expected) {
        assertThat(NetworkAudioUrl.isValid(url)).as("地址规则：%s", url).isEqualTo(expected);
    }
    @Test void allowsExactly2048CharactersWithoutRewriting() {
        String prefix = "https://media.example.com/";
        String url = prefix + "a".repeat(2048 - prefix.length());
        assertThat(NetworkAudioUrl.isValid(url)).isTrue();
        assertThat(NetworkAudioUrl.isValid(url + "a")).isFalse();
    }
}
