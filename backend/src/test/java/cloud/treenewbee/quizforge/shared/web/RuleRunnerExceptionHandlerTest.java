package cloud.treenewbee.quizforge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RuleRunnerExceptionHandlerTest {
    @Test void unavailableRunnerMapsTo503WithoutLeakingInternalDetails() {
        var response = new ApiExceptionHandler().handleRuleUnavailable(new RuleRunnerException("/private/blobs: internal-token=test"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("RULE_UNAVAILABLE");
        assertThat(response.getBody().error().message()).isEqualTo("规则服务暂时不可用，请稍后重试");
    }
}
