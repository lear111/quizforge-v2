package cloud.treenewbee.quizforge.practice.grading;

import cloud.treenewbee.quizforge.practice.application.GradeOutcome;
import cloud.treenewbee.quizforge.practice.resources.SubmissionImages;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 只验证格式和边界，不推断作答是否正确。 */
@Component
public class BrowserGradeFormat {
    public static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final BigDecimal MAX_DECIMAL = new BigDecimal("99999999.9999");
    private final JsonMapper json;

    public BrowserGradeFormat(JsonMapper json) { this.json = json; }

    public GradeOutcome parse(JsonNode report, JsonNode outline, JsonNode answer) {
        if (report == null || !report.isObject() || size(report) > MAX_OUTPUT_BYTES) throw invalid();
        JsonNode result = report.path("result"), projected = report.get("projected"), score = report.path("score");
        if (!result.isObject() || projected == null || projected.isNull() || !score.isObject()) throw invalid();
        String status = score.path("gradingStatus").asString("");
        if (!Set.of("graded", "pending").contains(status)) throw invalid();
        if (!status.equals(result.path("gradingStatus").asString("graded")) || !result.has("feedback")) throw invalid();
        BigDecimal resultMax = decimal(result.path("maxScore"));
        if (status.equals("pending")) {
            if (!result.path("score").isNull() || !result.path("correct").isNull()) throw invalid();
        } else if (!result.path("correct").isBoolean() || decimal(result.path("score")).compareTo(resultMax) > 0) throw invalid();
        BigDecimal max = decimal(score.path("maxScore"));
        BigDecimal value = null;
        if (status.equals("pending")) {
            if (!score.path("score").isNull()) throw invalid();
        } else {
            value = decimal(score.path("score"));
            if (value.compareTo(max) > 0) throw invalid();
        }
        JsonNode states = score.get("outlineStates");
        if (states != null && !states.isNull()) {
            if (!states.isArray() || states.size() > 100) throw invalid();
            Set<String> allowed = new HashSet<>(), seen = new HashSet<>();
            if (outline != null) for (JsonNode item : outline.path("items")) allowed.add(item.path("id").asString());
            for (JsonNode item : states) {
                String id = item.path("id").asString("");
                if (!item.isObject() || item.size() != 2 || !allowed.contains(id) || !seen.add(id)
                        || !Set.of("unanswered", "correct", "incorrect").contains(item.path("status").asString(""))) throw invalid();
            }
        }
        String frozen = SubmissionImages.freeze(json, projected, answer);
        if (frozen.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) throw invalid();
        return new GradeOutcome(json.writeValueAsString(result), frozen,
                value == null ? null : value.setScale(4, RoundingMode.HALF_UP), max.setScale(4, RoundingMode.HALF_UP),
                status, states == null || states.isNull() ? null : json.writeValueAsString(states));
    }

    private BigDecimal decimal(JsonNode value) {
        if (!value.isNumber()) throw invalid();
        try {
            var number = new BigDecimal(value.toString());
            if (number.signum() < 0 || number.compareTo(MAX_DECIMAL) > 0) throw invalid();
            return number;
        } catch (NumberFormatException error) { throw invalid(); }
    }

    private int size(JsonNode value) { return json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length; }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADING_REPORT", "浏览器判分结果格式或大小无效");
    }
}
