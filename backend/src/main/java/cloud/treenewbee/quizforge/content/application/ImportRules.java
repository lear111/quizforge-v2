package cloud.treenewbee.quizforge.content.application;

import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedExtension;
import cloud.treenewbee.quizforge.rules.RulePackage;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.rules.RuleRunnerProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request sizing uses the same mapper and complete envelope as RuleRunnerClient, including UTF-8 escapes. */
@Component
public class ImportRules {
    public static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final Set<String> EXPOSED_CODES = Set.of("RULE_TIMEOUT", "SCORE_UNAVAILABLE",
            "INVALID_OUTLINE_ITEMS", "INVALID_OUTLINE_STATES", "UNSUPPORTED_API_VERSION");
    private final RuleRunnerClient runner;
    private final RuleRunnerProperties properties;
    private final JsonMapper json;

    public ImportRules(RuleRunnerClient runner, RuleRunnerProperties properties, JsonMapper json) {
        this.runner = runner; this.properties = properties; this.json = json;
    }

    @SuppressWarnings("unchecked")
    public RulePackage spec(ParsedExtension extension) {
        Map<String, Object> manifest = extension.manifest();
        Map<String, Object> api = (Map<String, Object>) manifest.getOrDefault("requiresApi", Map.of("major", 1, "minMinor", 0));
        List<?> capabilities = (List<?>) api.getOrDefault("capabilities", List.of());
        return new RulePackage(extension.digest(), new RulePackage.ApiVersion(((Number) api.get("major")).intValue(),
                ((Number) api.get("minMinor")).intValue()), capabilities.contains("outline-items"), capabilities.contains("answer-state"),
                ref(extension, "rules"), ref(extension, "questionSchema"), ref(extension, "answerSchema"));
    }

    private RulePackage.FileRef ref(ParsedExtension extension, String field) {
        String relative = Path.of((String) extension.manifest().get(field)).normalize().toString().replace('\\', '/');
        return new RulePackage.FileRef(extension.files().get(relative));
    }

    public JsonNode call(RulePackage spec, String op, Map<String, ?> input) {
        try {
            var result = runner.execute(op, spec, input);
            if (!result.ok()) {
                String code = result.code() != null && EXPOSED_CODES.contains(result.code()) ? result.code() : "RULE_REJECTED";
                throw new ImportFailure(code, "拓展规则拒绝了题库内容（" + code + "）");
            }
            if (result.data() == null || !result.data().isObject()) throw unavailable();
            return result.data();
        } catch (RuleRunnerException error) { throw unavailable(); }
    }

    public List<JsonNode> batch(RulePackage spec, String op, List<?> items, String outputField) {
        int overhead = envelopeBytes(spec, op, Map.of("questions", List.of()));
        List<JsonNode> output = new ArrayList<>();
        List<Object> chunk = new ArrayList<>();
        long bytes = overhead;
        for (Object item : items) {
            int size = json.writeValueAsBytes(item).length;
            if ((long) overhead + size > MAX_REQUEST_BYTES) {
                throw new ImportFailure("LIBRARY_ARCHIVE_LIMIT", "单道题目超过规则请求的 8 MiB 上限");
            }
            if (bytes + size + (chunk.isEmpty() ? 0 : 1) > MAX_REQUEST_BYTES) {
                append(output, spec, op, chunk, outputField);
                chunk = new ArrayList<>(); bytes = overhead;
            }
            bytes += size + (chunk.isEmpty() ? 0 : 1);
            chunk.add(item);
        }
        if (!chunk.isEmpty()) append(output, spec, op, chunk, outputField);
        return List.copyOf(output);
    }

    private void append(List<JsonNode> output, RulePackage spec, String op, List<?> chunk, String outputField) {
        JsonNode result = call(spec, op, Map.of("questions", chunk));
        if (outputField == null) {
            if (!result.path("valid").asBoolean(false)) throw unavailable();
        } else {
            JsonNode values = result.path(outputField);
            if (!values.isArray() || values.size() != chunk.size()) throw unavailable();
            values.forEach(output::add);
        }
    }

    int envelopeBytes(RulePackage spec, String op, Map<String, ?> input) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", 1); envelope.put("requestId", "00000000-0000-0000-0000-000000000000");
        envelope.put("op", op); envelope.put("deadlineMs", properties.defaultDeadline().toMillis());
        envelope.put("package", spec); envelope.put("input", input);
        return json.writeValueAsBytes(envelope).length;
    }

    static ImportFailure unavailable() { return new ImportFailure("RULE_UNAVAILABLE", "规则服务暂时不可用，请稍后重试"); }
}
