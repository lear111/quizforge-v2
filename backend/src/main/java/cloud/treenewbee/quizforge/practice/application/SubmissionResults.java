package cloud.treenewbee.quizforge.practice.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Present the same four-decimal scores as the persisted submission, preserving extension result details. */
final class SubmissionResults {
    private SubmissionResults() {}
    static JsonNode result(JsonMapper json, String raw, BigDecimal score, BigDecimal maxScore, String status) {
        JsonNode original = raw == null ? null : json.readTree(raw);
        if (status == null) return original;
        ObjectNode result = original instanceof ObjectNode object ? object.deepCopy() : json.createObjectNode();
        result.put("score", scale(score)); result.put("maxScore", scale(maxScore)); result.put("gradingStatus", status);
        return result;
    }
    private static BigDecimal scale(BigDecimal value) { return value == null ? null : value.setScale(4, RoundingMode.HALF_UP); }
}
