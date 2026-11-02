package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import cloud.treenewbee.quizforge.practice.infrastructure.DraftOutlineMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper;
import cloud.treenewbee.quizforge.rules.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DraftOutlineServiceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final PracticeReadService reads = mock(PracticeReadService.class);
    final RuleRunnerClient runner = mock(RuleRunnerClient.class);
    final DraftOutlineService service = new DraftOutlineService(reads, runner, mock(DraftOutlineMapper.class),
            mock(ReceiptMapper.class), mock(TransactionTemplate.class), json, Duration.ofMillis(250));

    void source(boolean declared) {
        var spec = new RulePackage("a".repeat(64), new RulePackage.ApiVersion(1, 3), false, declared, null, null, null);
        when(reads.ruleQuestion("u", "c", "q")).thenReturn(new PracticeReadService.RuleQuestion(
                json.readTree("{\"standardAnswer\":\"不可外泄\"}"), spec,
                json.readTree("{\"level\":\"parts\",\"items\":[{\"id\":\"p1\"},{\"id\":\"p2\"}]}")));
    }
    DraftOutlineService.Progress calculate(String answer, String reported) {
        return service.calculate("u", "c", "q", 7, json.readTree(answer), reported == null ? null : json.readTree(reported));
    }
    void result(String body) {
        when(runner.execute(eq("validateAnswer"), any(), anyMap(), eq(Duration.ofMillis(250))))
                .thenReturn(RuleResult.success(json.readTree(body)));
    }

    @Test void onlyDraftStatusesAndKnownIdsEscapeTheRuleResponse() {
        source(false);
        result("{\"valid\":true,\"projected\":{\"answer\":\"secret\"},\"outlineScore\":{\"score\":0,\"outlineStates\":[{\"id\":\"p1\",\"status\":\"draft\"},{\"id\":\"p2\",\"status\":\"unanswered\"}]}}");
        var value = calculate("{\"text\":\"a\"}", null);
        assertThat(value.outlineStates().get(0).path("status").asString()).isEqualTo("draft");
        assertThat(json.writeValueAsString(value)).doesNotContain("secret", "standardAnswer", "score", "projected");
        verify(runner).execute(eq("validateAnswer"), any(), argThat(input -> input.get("outlineRevision").equals(7L)
                && Boolean.TRUE.equals(input.get("outlineAfterValidation"))), eq(Duration.ofMillis(250)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "incorrect", "pending", "unknown"})
    void gradingStatusesAreNotAcceptedAsDraftFeedback(String status) {
        source(false);
        result("{\"valid\":true,\"outlineScore\":{\"outlineStates\":[{\"id\":\"p1\",\"status\":\"" + status + "\"}]}}");
        assertThat(calculate("{}", null).outlineStates()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[{\"id\":\"other\",\"status\":\"draft\"}]", "[{\"id\":\"p1\",\"status\":\"draft\"},{\"id\":\"p1\",\"status\":\"unanswered\"}]"})
    void foreignOrDuplicateIdsMakeProgressUnknown(String states) {
        source(false);
        result("{\"valid\":true,\"outlineScore\":{\"outlineStates\":" + states + "}}");
        assertThat(calculate("{}", null).outlineStates()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "\"  \"", "{\"a\":\"\"}"})
    void emptyFallbackAnswersHaveNoAnsweredChildren(String answer) {
        source(false);
        result("{\"valid\":true}");
        assertThat(calculate(answer, null).outlineStates().valueStream().map(n -> n.path("status").asString()))
                .containsExactly("unanswered", "unanswered");
    }

    @Test void booleanFalseIsAnAnswerAndReportedStateOnlyAppliesToDeclaredExtensions() {
        source(false);
        result("{\"valid\":true}");
        assertThat(calculate("false", "{\"outlineStates\":[]}").outlineStates().get(0).path("status").asString()).isEqualTo("draft");
        source(true);
        assertThat(calculate("false", "{\"outlineStates\":[{\"id\":\"p2\",\"status\":\"draft\"}]}")
                .outlineStates().get(0).path("status").asString()).isEqualTo("unanswered");
    }

    @Test void rejectionOrUnavailableRunnerIsUnknown() {
        source(false);
        when(runner.execute(eq("validateAnswer"), any(), anyMap(), any(Duration.class)))
                .thenReturn(RuleResult.rejected("RULE_TIMEOUT"));
        assertThat(calculate("{}", null).answerState()).isNull();
        when(runner.execute(eq("validateAnswer"), any(), anyMap(), any(Duration.class)))
                .thenThrow(new RuleRunnerException("不可用"));
        assertThat(calculate("{}", null).outlineStates()).isNull();
    }
}
