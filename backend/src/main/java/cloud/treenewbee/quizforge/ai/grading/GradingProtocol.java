package cloud.treenewbee.quizforge.ai.grading;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 旧版 API 的材料与候选格式；分值范围、步长交给拓展 review 决定。 */
@Component
public class GradingProtocol {
    private final JsonMapper json;

    public GradingProtocol(JsonMapper json) { this.json = json; }

    public ObjectNode input(JsonNode value) {
        require(value != null && value.isObject(), "评分材料必须是对象");
        only(value, Set.of("protocolVersion", "question", "answer", "referenceAnswer", "rubric", "maxScore", "scoreStep"));
        require(value.path("protocolVersion").isIntegralNumber() && value.path("protocolVersion").asInt() == 1, "不支持的评分材料版本");
        for (String field : Set.of("maxScore", "scoreStep")) {
            JsonNode n = value.path(field);
            require(n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0, field + " 必须为正数");
        }
        int[] budget = {0, 0};
        blocks(value.path("question"), false, budget);
        blocks(value.path("answer"), false, budget);
        boolean rubric = value.has("rubric");
        blocks(value.path("referenceAnswer"), rubric, budget);
        if (rubric) blocks(value.path("rubric"), false, budget);
        if (json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "AI_INPUT_TOO_LARGE", "评分材料超过 1 MiB");
        ObjectNode result = (ObjectNode) value.deepCopy();
        if (rubric) {
            var merged = json.createArrayNode();
            if (!value.path("referenceAnswer").isEmpty()) {
                merged.add(json.createObjectNode().put("type", "text").put("text", "参考答案："));
                value.path("referenceAnswer").forEach(merged::add);
            }
            merged.add(json.createObjectNode().put("type", "text").put("text", "评分依据："));
            value.path("rubric").forEach(merged::add);
            result.set("referenceAnswer", merged);
            result.remove("rubric");
        }
        return result;
    }

    public ObjectNode candidate(String text) {
        JsonNode value;
        try { value = json.readTree(text); }
        catch (RuntimeException invalid) { throw invalid("模型没有返回有效 JSON"); }
        require(value != null && value.isObject() && value.size() == 2 && value.has("score") && value.has("feedback"), "模型应返回 score 和 feedback");
        require(value.path("score").isNumber() && Double.isFinite(value.path("score").asDouble()), "模型分数必须为有限数");
        require(value.path("feedback").isString() && !value.path("feedback").asString().isBlank()
                && value.path("feedback").asString().length() <= 18000, "模型反馈为空或超过 18000 字符");
        ObjectNode result = (ObjectNode) value.deepCopy();
        result.put("feedback", value.path("feedback").asString().trim());
        return result;
    }

    public static String target(String value) {
        if (value == null) return "";
        require(!value.isEmpty() && value.length() <= 128 && !space(value.codePointAt(0)) && !space(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(c -> c < 32 || c >= 127 && c <= 159 || c == '<' || c == '>'), "评分目标编号无效");
        return value;
    }

    private void blocks(JsonNode values, boolean emptyAllowed, int[] budget) {
        require(values.isArray() && values.size() <= 2000 && (emptyAllowed || !values.isEmpty()), "评分材料内容列表无效");
        for (JsonNode block : values) {
            require(block.isObject(), "评分材料内容必须是对象");
            if (block.path("type").asString().equals("text")) {
                only(block, Set.of("type", "text"));
                require(block.path("text").isString(), "评分材料文本无效");
                budget[0] += block.path("text").asString().length();
                require(budget[0] <= 400000, "评分材料文本超过上限");
            } else {
                only(block, Set.of("type", "assetId", "alt"));
                require(block.path("type").asString().equals("image") && block.path("assetId").isString()
                        && block.path("assetId").asString().matches("[a-f0-9]{64}"), "评分材料图片编号无效");
                require(!block.has("alt") || block.path("alt").isString() && block.path("alt").asString().length() <= 500, "图片说明超过上限");
                require(++budget[1] <= 40, "评分材料图片超过 40 张");
            }
        }
    }

    private static void only(JsonNode value, Set<String> fields) {
        for (String name : value.propertyNames()) require(fields.contains(name), "评分材料包含未知字段：" + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static boolean space(int value) { return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == 0xfeff; }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_AI_INPUT", message);
    }
}
