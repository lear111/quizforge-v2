package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class EcmaJsonTest {
    static Stream<Arguments> numbers() throws Exception {
        try (InputStream input = EcmaJsonTest.class.getResourceAsStream("/content/ecma-number-vectors.json")) {
            return JsonMapper.builder().build().readTree(input).valueStream().map(node -> Arguments.of(node.get("bits").asString(), node.get("expected").asString()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("numbers")
    void binary64FormattingMatchesNode(String bits, String expected) {
        assertThat(EcmaJson.number(Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16)))).isEqualTo(expected);
    }

    @Test
    void rejectsInvalidAndTrailingJson() {
        for (String text : new String[] {"", "{} {}", "{", "[1,]", "NaN", "Infinity", "01", "\ufeff{}"}) {
            assertThatThrownBy(() -> EcmaJson.parse(text.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
