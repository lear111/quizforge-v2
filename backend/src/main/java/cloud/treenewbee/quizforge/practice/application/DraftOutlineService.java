package cloud.treenewbee.quizforge.practice.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 进度仅用于用户自己的显示，不校验题型答案，不参与评分与权限判断。 */
@Service
public class DraftOutlineService {
    public record Progress(JsonNode answerState, JsonNode outlineStates) {}
    private final JsonMapper json;

    public DraftOutlineService(JsonMapper json) { this.json = json; }

    public Progress calculate(JsonNode outline, boolean declared, JsonNode answer, JsonNode reported) {
        if (!declared || reported == null || reported.isNull()) return fallback(answer);
        Set<String> ids = new LinkedHashSet<>();
        if (outline != null) for (JsonNode item : outline.path("items")) ids.add(item.path("id").asString());
        String whole = reported.path("status").asString();
        if (!Set.of("unanswered", "draft").contains(whole)) return unknown();
        JsonNode provided = reported.path("outlineStates");
        if (!provided.isArray()) return unknown();
        if (provided.isEmpty()) {
            var state = json.createObjectNode().put("status", whole);
            state.set("outlineStates", json.nullNode());
            return new Progress(state, null);
        }
        Map<String, String> states = new LinkedHashMap<>();
        for (JsonNode item : provided) {
            String id = item.path("id").asString(), value = item.path("status").asString();
            if (!ids.contains(id) || !Set.of("unanswered", "draft").contains(value)
                    || states.putIfAbsent(id, value) != null) return unknown();
        }
        var children = json.createArrayNode();
        for (String id : ids) children.addObject().put("id", id).put("status", states.getOrDefault(id, "unanswered"));
        var state = json.createObjectNode().put("status", states.containsValue("draft") ? "draft" : "unanswered");
        state.set("outlineStates", children);
        return new Progress(state, ids.isEmpty() ? null : children);
    }

    private Progress fallback(JsonNode answer) {
        var state = json.createObjectNode().put("status", nonempty(answer) ? "draft" : "unanswered");
        state.set("outlineStates", json.nullNode());
        return new Progress(state, null);
    }
    private static boolean nonempty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isString()) return !value.asString().isBlank();
        if (value.isArray() || value.isObject()) return value.valueStream().anyMatch(DraftOutlineService::nonempty);
        return true;
    }
    private static Progress unknown() { return new Progress(null, null); }
}
