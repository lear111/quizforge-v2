package cloud.treenewbee.quizforge.content.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.rules.RulePackage;
import cloud.treenewbee.quizforge.rules.RuleResult;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ImportRulesTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RuleRunnerClient runner = mock(RuleRunnerClient.class);
    private final ImportRules rules = new ImportRules(runner,
            new RuleRunnerProperties(URI.create("http://127.0.0.1"), "test", 2, Duration.ofSeconds(8)), json);
    private final RulePackage spec = new RulePackage("a".repeat(64), new RulePackage.ApiVersion(1,5), true, false,
            new RulePackage.FileRef("b".repeat(64)), new RulePackage.FileRef("c".repeat(64)), new RulePackage.FileRef("d".repeat(64)));

    @Test
    void singleItemThatCannotFitItsEnvelopeIsRejectedBeforeNetwork() {
        assertThatThrownBy(() -> rules.batch(spec, "validateBank", List.of("x".repeat(ImportRules.MAX_REQUEST_BYTES)), null))
                .isInstanceOf(ImportFailure.class).extracting(error -> ((ImportFailure)error).code()).isEqualTo("LIBRARY_ARCHIVE_LIMIT");
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"scores\":true}", "{\"scores\":[]}"})
    void malformedBatchResultCannotSilentlyDropQuestions(String response) {
        when(runner.execute(anyString(), any(), anyMap())).thenReturn(RuleResult.success(json.readTree(response)));
        assertThatThrownBy(() -> rules.batch(spec, "scoreBatch", List.of(Map.of()), "scores"))
                .isInstanceOf(ImportFailure.class).extracting(error -> ((ImportFailure)error).code()).isEqualTo("RULE_UNAVAILABLE");
    }
}
