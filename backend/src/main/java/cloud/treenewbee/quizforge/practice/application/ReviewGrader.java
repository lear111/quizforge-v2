package cloud.treenewbee.quizforge.practice.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Review and getScore execute before any write transaction; the extension owns grading semantics. */
@Component
public class ReviewGrader {
    private final PracticeReadService reads;
    private final RuleRunnerClient runner;
    private final JsonMapper json;
    public ReviewGrader(PracticeReadService reads, RuleRunnerClient runner, JsonMapper json) {
        this.reads = reads; this.runner = runner; this.json = json;
    }

    public GradeOutcome grade(String userId, String collectionId, String qid, JsonNode answer, JsonNode state, JsonNode review) {
        var source = reads.ruleQuestion(userId, collectionId, qid);
        var reviewed = execute("review", source, Map.of("data", source.data(), "answer", answer,
                "state", state, "review", review));
        if (!reviewed.hasNonNull("result") || !reviewed.hasNonNull("projected")) throw invalid();
        var nextState = state.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) nextState).set("result", reviewed.get("result"));
        var scored = execute("scoreBatch", source, Map.of("questions", List.of(Map.of("data", source.data(), "state", nextState))));
        if (!scored.path("scores").isArray() || scored.path("scores").size() != 1) throw invalid();
        var score = scored.path("scores").get(0);
        String status = score.path("gradingStatus").asString("");
        if (!Set.of("graded", "pending").contains(status) || !score.path("maxScore").isNumber()
                || ("pending".equals(status) ? !score.path("score").isNull() : !score.path("score").isNumber())
                || (score.has("outlineStates") && !score.get("outlineStates").isArray())) throw invalid();
        return new GradeOutcome(json.writeValueAsString(reviewed.get("result")), cloud.treenewbee.quizforge.practice.resources.SubmissionImages.freeze(json,reviewed.get("projected"),answer),
                "pending".equals(status) ? null : new BigDecimal(score.get("score").toString()),
                new BigDecimal(score.get("maxScore").toString()), status,
                score.has("outlineStates") ? json.writeValueAsString(score.get("outlineStates")) : null);
    }

    private JsonNode execute(String op, PracticeReadService.RuleQuestion source, Map<String, Object> input) {
        var result = runner.execute(op, source.rulePackage(), input);
        if (!result.ok()) {
            String code = result.code() != null && Set.of("RULE_TIMEOUT", "SCORE_UNAVAILABLE", "INVALID_OUTLINE_STATES",
                    "UNSUPPORTED_API_VERSION").contains(result.code()) ? result.code() : "RULE_REJECTED";
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "题型无法处理此评分（" + code + "）");
        }
        if (result.data() == null || !result.data().isObject()) throw invalid();
        return result.data();
    }
    private static RuleRunnerException invalid() { return new RuleRunnerException("rule-runner 返回了不完整的人工评分结果"); }
}
