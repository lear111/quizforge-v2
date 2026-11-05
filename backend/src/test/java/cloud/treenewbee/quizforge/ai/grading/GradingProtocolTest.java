package cloud.treenewbee.quizforge.ai.grading;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;

class GradingProtocolTest {
    final JsonMapper json = JsonMapper.builder().build();
    final GradingProtocol protocol = new GradingProtocol(json);

    @Test void scoreRangeAndStepBelongToExtensionReview() {
        for (String score : new String[] {"-1", "1.234567", "100000000000"})
            assertThat(protocol.candidate("{\"score\":" + score + ",\"feedback\":\" 有依据 \"}").path("feedback").asString()).isEqualTo("有依据");
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "bad", "{\"score\":1}",
            "{\"score\":\"1\",\"feedback\":\"好\"}", "{\"score\":1e309,\"feedback\":\"好\"}",
            "{\"score\":1,\"feedback\":\" \"}", "{\"score\":1,\"feedback\":\"好\",\"extra\":true}"})
    void rejectsMalformedCandidate(String value) { assertThatThrownBy(() -> protocol.candidate(value)).isInstanceOf(ApiException.class); }

    @Test void legacyRubricMergesIntoReferenceWithoutMutatingAuthorInput() {
        var input = json.readTree("{\"protocolVersion\":1,\"question\":[{\"type\":\"text\",\"text\":\"题目\"}],\"answer\":[{\"type\":\"text\",\"text\":\"作答\"}],\"referenceAnswer\":[],\"rubric\":[{\"type\":\"text\",\"text\":\"依据\"}],\"maxScore\":3,\"scoreStep\":0.5}");
        var normalized = protocol.input(input);
        assertThat(normalized.has("rubric")).isFalse();
        assertThat(normalized.path("referenceAnswer").toString()).contains("评分依据", "依据");
        assertThat(input.has("rubric")).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"", " a", "a ", "<p>", "a\n", "a\u0080"})
    void rejectsInvalidTargets(String id) { assertThatThrownBy(() -> GradingProtocol.target(id)).isInstanceOf(ApiException.class); }

    @Test void targetMayContainUnicodeButHasLegacyLengthLimit() {
        assertThat(GradingProtocol.target("第一小问")).isEqualTo("第一小问");
        assertThat(GradingProtocol.target(null)).isEmpty();
        assertThatThrownBy(() -> GradingProtocol.target("a".repeat(129))).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"\u00a0", "\ufeff", "\u3000"})
    void rejectsEcmaWhitespaceOnlyFeedback(String whitespace) {
        assertThatThrownBy(() -> protocol.candidate(json.writeValueAsString(java.util.Map.of("score", 1, "feedback", whitespace))))
                .isInstanceOf(ApiException.class);
    }

    @Test void trimsEcmaWhitespaceAroundFeedback() {
        assertThat(protocol.candidate(json.writeValueAsString(java.util.Map.of("score", 1, "feedback", "\u00a0有依据\ufeff")))
                .path("feedback").asString()).isEqualTo("有依据");
    }

    @Test void rejectsExcessFeedback() {
        assertThatThrownBy(() -> protocol.candidate(json.writeValueAsString(java.util.Map.of("score", 1, "feedback", "字".repeat(18001)))))
                .isInstanceOf(ApiException.class);
    }
}
