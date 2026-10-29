package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionContentHashTest {
    @Test
    void stableVersionIncludesBindingEffectiveOutlineListeningAndAssets() {
        var question = Map.<String,Object>of("id", "q1", "data", Map.of("x", 1));
        String initial = QuestionContentHash.compute(question, "a".repeat(64), null, null, Map.of());
        assertThat(QuestionContentHash.compute(question, "a".repeat(64), null, null, Map.of())).isEqualTo(initial);
        assertThat(QuestionContentHash.compute(question, "b".repeat(64), null, null, Map.of())).isNotEqualTo(initial);
        assertThat(QuestionContentHash.compute(question, "a".repeat(64), List.of(Map.of("id","part","label","小题")), null, Map.of())).isNotEqualTo(initial);
        assertThat(QuestionContentHash.compute(question, "a".repeat(64), null, Map.of("tracks", List.of("audio.mp3")), Map.of())).isNotEqualTo(initial);
        assertThat(QuestionContentHash.compute(question, "a".repeat(64), null, null, Map.of("image.png", "c".repeat(64)))).isNotEqualTo(initial);
    }
}
