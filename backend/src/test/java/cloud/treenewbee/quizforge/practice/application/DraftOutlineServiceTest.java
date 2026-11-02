package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DraftOutlineServiceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final DraftOutlineService service = new DraftOutlineService(json);
    final JsonNode outline = json.readTree("{\"level\":\"parts\",\"items\":[{\"id\":\"p1\"},{\"id\":\"p2\"}]}");

    DraftOutlineService.Progress reported(String states) {
        return service.calculate(outline, true, json.readTree("{}"),
                json.readTree("{\"status\":\"draft\",\"outlineStates\":" + states + "}"));
    }

    @Test void reportedProgressOnlyContainsKnownIdsAndDraftStatuses() {
        var value = reported("[{\"id\":\"p1\",\"status\":\"draft\",\"standardAnswer\":\"secret\"}]");
        assertThat(value.outlineStates()).isEqualTo(json.readTree("[{\"id\":\"p1\",\"status\":\"draft\"},{\"id\":\"p2\",\"status\":\"unanswered\"}]"));
        assertThat(json.writeValueAsString(value)).doesNotContain("secret", "standardAnswer", "score", "projected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "incorrect", "pending", "unknown"})
    void gradingStatusesAreNotAcceptedAsDraftFeedback(String status) {
        assertThat(reported("[{\"id\":\"p1\",\"status\":\"" + status + "\"}]").outlineStates()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[{\"id\":\"other\",\"status\":\"draft\"}]", "[{\"id\":\"p1\",\"status\":\"draft\"},{\"id\":\"p1\",\"status\":\"unanswered\"}]", "null", "{}"})
    void foreignDuplicateIdsOrMalformedReportsMakeProgressUnknown(String states) {
        assertThat(reported(states).outlineStates()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "\"  \"", "{\"a\":\"\"}"})
    void emptyFallbackAnswersLeaveChildrenUnknown(String answer) {
        var value = service.calculate(outline, false, json.readTree(answer), null);
        assertThat(value.answerState().path("status").asString()).isEqualTo("unanswered");
        assertThat(value.outlineStates()).isNull();
    }

    @Test void undeclaredExtensionsIgnoreReportsAndFalseIsNonemptyAnswer() {
        var value = service.calculate(outline, false, json.readTree("false"),
                json.readTree("{\"status\":\"unanswered\",\"outlineStates\":[]}"));
        assertThat(value.answerState().path("status").asString()).isEqualTo("draft");
        assertThat(value.outlineStates()).isNull();
    }

    @Test void wholeQuestionReportDoesNotInventChildProgress() {
        var value = reported("[]");
        assertThat(value.answerState().path("status").asString()).isEqualTo("draft");
        assertThat(value.outlineStates()).isNull();
    }
}
