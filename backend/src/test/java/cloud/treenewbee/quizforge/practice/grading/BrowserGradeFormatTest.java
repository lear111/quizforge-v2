package cloud.treenewbee.quizforge.practice.grading;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BrowserGradeFormatTest {
    final JsonMapper json = JsonMapper.builder().build();
    final BrowserGradeFormat format = new BrowserGradeFormat(json);
    JsonNode valid() { return json.readTree("""
            {"result":{"correct":true,"score":1,"maxScore":2,"feedback":"自主练习"},
             "projected":{"stem":"题干","explanation":"解析"},
             "score":{"score":1.23456,"maxScore":2,"gradingStatus":"graded","outlineStates":[{"id":"a","status":"correct"}]}}
            """); }
    JsonNode outline() { return json.readTree("{\"level\":\"parts\",\"items\":[{\"id\":\"a\",\"label\":\"小题\"}]}"); }

    @Test void storesBrowserOutcomeWithoutJudgingCorrectnessAndRoundsHalfUp() {
        var outcome = format.parse(valid(), outline(), json.nullNode());
        assertThat(outcome.score()).isEqualByComparingTo(new BigDecimal("1.2346"));
        assertThat(outcome.maxScore()).isEqualByComparingTo("2");
        assertThat(outcome.resultJson()).contains("自主练习");
    }

    @ParameterizedTest @ValueSource(strings={"-1","3","\"1\"","null","1e100"})
    void rejectsInvalidScores(String score) {
        var value = (tools.jackson.databind.node.ObjectNode) valid().path("score");
        value.set("score", json.readTree(score));
        var report = (tools.jackson.databind.node.ObjectNode) valid();
        report.set("score", value);
        assertThatThrownBy(() -> format.parse(report, outline(), null)).hasMessageContaining("格式或大小无效");
    }

    @Test void rejectsForeignDuplicateAndDraftOutlineStates() {
        for (String states : new String[]{"[{\"id\":\"other\",\"status\":\"correct\"}]",
                "[{\"id\":\"a\",\"status\":\"correct\"},{\"id\":\"a\",\"status\":\"incorrect\"}]",
                "[{\"id\":\"a\",\"status\":\"draft\"}]"}) {
            var report = (tools.jackson.databind.node.ObjectNode) valid();
            ((tools.jackson.databind.node.ObjectNode) report.path("score")).set("outlineStates", json.readTree(states));
            assertThatThrownBy(() -> format.parse(report, outline(), null)).hasMessageContaining("格式或大小无效");
        }
    }

    @Test void rejectsOversizedProjectionMeasuredInUtf8Bytes() {
        var report = (tools.jackson.databind.node.ObjectNode) valid();
        report.put("projected", "中".repeat(700000));
        assertThatThrownBy(() -> format.parse(report, outline(), null)).hasMessageContaining("格式或大小无效");
    }

    @Test void preservesPendingNullScore() {
        var report = (tools.jackson.databind.node.ObjectNode) valid();
        var score = (tools.jackson.databind.node.ObjectNode) report.path("score");
        score.put("gradingStatus", "pending").putNull("score");
        ((tools.jackson.databind.node.ObjectNode) report.path("result")).put("gradingStatus", "pending").putNull("score").putNull("correct");
        assertThat(format.parse(report, outline(), null).score()).isNull();
    }

    @Test void validatesModeAndFallbackTimeout() {
        assertThat(new GradingMode("browser", Duration.ofMinutes(2)).browser()).isTrue();
        assertThat(new GradingMode("server", Duration.ofMinutes(2)).browser()).isFalse();
        assertThatThrownBy(() -> new GradingMode("invalid", Duration.ofMinutes(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GradingMode("browser", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
