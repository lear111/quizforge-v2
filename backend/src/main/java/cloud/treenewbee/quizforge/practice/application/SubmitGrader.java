package cloud.treenewbee.quizforge.practice.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs outside the caller's write transaction; never changes practice state. */
@Component
public class SubmitGrader {
    private static final Set<String> REJECTION_CODES = Set.of("RULE_REJECTED", "RULE_TIMEOUT", "SCORE_UNAVAILABLE",
            "INVALID_OUTLINE_ITEMS", "INVALID_OUTLINE_STATES", "UNSUPPORTED_API_VERSION");
    private final PracticeReadService reads;
    private final RuleRunnerClient runner;
    private final JsonMapper json;

    public SubmitGrader(PracticeReadService reads, RuleRunnerClient runner, JsonMapper json) {
        this.reads = reads; this.runner = runner; this.json = json;
    }

    public GradeOutcome grade(String userId, String collectionId, String questionId, JsonNode answer) {
        var question = reads.ruleQuestion(userId, collectionId, questionId);
        var response = runner.execute("submit", question.rulePackage(), Map.of("data", question.data(),
                "answer", answer == null ? json.nullNode() : answer, "withScore", true));
        if (!response.ok()) {
            String code = response.code() != null && REJECTION_CODES.contains(response.code()) ? response.code() : "RULE_REJECTED";
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "题型无法处理此作答（" + code + "）");
        }
        JsonNode data = response.data();
        if (data == null || !data.hasNonNull("result") || !data.hasNonNull("projected")) throw invalidResponse();
        JsonNode score = data.path("score");
        String status = score.path("gradingStatus").asString("");
        if (!Set.of("graded", "pending").contains(status) || !score.path("maxScore").isNumber()
                || (status.equals("pending") ? !score.path("score").isNull() : !score.path("score").isNumber())
                || (score.has("outlineStates") && !score.get("outlineStates").isArray())) throw invalidResponse();
        return new GradeOutcome(json.writeValueAsString(data.get("result")), json.writeValueAsString(data.get("projected")),
                status.equals("pending") ? null : decimal(score.get("score")), decimal(score.get("maxScore")), status,
                score.has("outlineStates") ? json.writeValueAsString(score.get("outlineStates")) : null);
    }

    private static BigDecimal decimal(JsonNode value) {
        // Preserve the runner's number; DECIMAL persistence/rounding policy belongs to the write flow.
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException invalid) { throw invalidResponse(); }
    }
    private static RuleRunnerException invalidResponse() { return new RuleRunnerException("rule-runner 返回了不完整的判分结果"); }
}
