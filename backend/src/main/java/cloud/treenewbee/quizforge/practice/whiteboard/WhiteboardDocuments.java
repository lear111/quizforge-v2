package cloud.treenewbee.quizforge.practice.whiteboard;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates only the current question's ink, never the question bank. */
@Component
public class WhiteboardDocuments {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final JsonMapper json;
    public WhiteboardDocuments(JsonMapper json) { this.json = json; }

    // Match MySQL JSON key ordering so a new empty board serializes identically in receipt replays.
    public ObjectNode empty() {
        return (ObjectNode) json.readTree("{\"paper\":{\"color\":\"#ffffff\",\"pattern\":\"plain\"},\"strokes\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1},\"schemaVersion\":1}");
    }
    public JsonNode normalize(JsonNode value) {
        if (value == null || !value.isObject() || value.path("schemaVersion").asInt() != 1
                || !value.path("strokes").isArray() || value.path("strokes").size() > 2000) throw invalid();
        if (bytes(value) > MAX_BYTES) throw tooLarge();
        var viewport = value.path("viewport");
        number(viewport.path("x"), -1_000_000, 1_000_000); number(viewport.path("y"), -1_000_000, 1_000_000);
        number(viewport.path("zoom"), .25, 4);
        var paper = value.path("paper");
        color(paper.path("color"));
        if (!java.util.Set.of("plain","grid","dots").contains(paper.path("pattern").asString(""))) throw invalid();
        if (value.has("layout")) {
            number(value.path("layout").path("width"),100,10000);
            number(value.path("layout").path("padding"),0,100);
            number(value.path("layout").path("top"),0,200);
        }
        var ids = new HashSet<String>(); int points = 0;
        for (var stroke : value.get("strokes")) {
            String id = stroke.path("id").asString("");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") || !ids.add(id)) throw invalid();
            color(stroke.path("color")); number(stroke.path("width"),.5,32);
            var path = stroke.path("points");
            if (!path.isArray() || path.isEmpty() || path.size()>10000 || (points+=path.size())>100000) throw invalid();
            for (var point : path) {
                number(point.path("x"),-1_000_000,1_000_000); number(point.path("y"),-1_000_000,1_000_000);
                if (point.has("pressure")) number(point.get("pressure"),0,1);
            }
        }
        // Only the public whiteboard document fields survive normalization.
        var result = json.createObjectNode();
        for (String field : java.util.List.of("schemaVersion","viewport","strokes","paper","layout"))
            if (value.has(field)) result.set(field,value.get(field).deepCopy());
        return result;
    }
    /** 两个阶段只能写当前层；完成整轮后未作答题的草稿也冻结。 */
    public static boolean draftLocked(String roundStatus, boolean submitted) {
        return submitted || "completed".equals(roundStatus);
    }
    public static void requireWritableLayer(String layer, String roundStatus, boolean submitted) {
        boolean locked = draftLocked(roundStatus, submitted);
        if (layer.equals("draft") && locked)
            throw new ApiException(HttpStatus.CONFLICT, "WHITEBOARD_DRAFT_LOCKED", "作答草稿已冻结，请在追加笔迹中书写");
        if (layer.equals("notes") && !locked)
            throw new ApiException(HttpStatus.CONFLICT, "WHITEBOARD_NOTES_UNAVAILABLE", "提交前请在作答草稿中书写");
    }
    /** 防止追加层复用冻结笔迹 ID，避免渲染时覆盖原笔迹。 */
    public void requireSeparateLayers(JsonNode frozen, JsonNode next) {
        var ids = new HashSet<String>();
        for (var stroke : frozen.path("strokes")) ids.add(stroke.path("id").asString());
        for (var stroke : next.path("strokes")) if (ids.contains(stroke.path("id").asString())) throw invalid();
    }
    public int bytes(JsonNode value) { return json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length; }
    private static void number(JsonNode value, double min, double max) {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue()<min || value.doubleValue()>max) throw invalid();
    }
    private static void color(JsonNode value) { if (!value.isString() || !value.asString().matches("#[a-fA-F0-9]{6}")) throw invalid(); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","白板笔迹格式无效"); }
    public static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"DRAFT_SIZE_LIMIT","两层白板合计超过 2 MiB 限制"); }
}
