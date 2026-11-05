package cloud.treenewbee.quizforge.ai.web;

import java.security.Principal;
import java.time.Clock;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.ai.grading.AiGradingMapper;
import cloud.treenewbee.quizforge.ai.grading.AiGradingService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 只接受会话用户；响应不序列化任务实体与服务器评分材料。 */
@RestController
public class AiGradingController {
    private static final String BASE = "/api/collections/{kind}/{id}/questions/{qid}/ai";
    public record Grade(@NotBlank @Size(max = 64) String requestId, @Size(max = 128) String targetId, Boolean force, @Size(max = 64) String contentVersion) {}
    public record Retry(@NotBlank @Size(max = 64) String requestId) {}
    public record Confirm(@NotBlank @Size(max = 64) String requestId, @Size(max = 36) String candidateVersion, Double score) {}
    public record Settings(boolean autoGrade) {}
    private final AiGradingService service;
    private final AiGradingMapper tasks;
    private final Clock clock;
    private final JsonMapper json;
    private final Validator validator;

    public AiGradingController(AiGradingService service, AiGradingMapper tasks, Clock clock, JsonMapper json, Validator validator) {
        this.service = service; this.tasks = tasks; this.clock = clock; this.json = json; this.validator = validator;
    }

    @PostMapping(value = BASE + "/grade", consumes = "application/json")
    public ResponseEntity<JsonNode> grade(Principal user, @PathVariable String kind, @PathVariable String id,
            @PathVariable String qid, HttpServletRequest request) throws IOException {
        Grade body = body(request, Grade.class);
        return response(service.grade(user.getName(), kind, id, qid, body.requestId(), body.targetId(), Boolean.TRUE.equals(body.force())));
    }

    @GetMapping(BASE + "/current")
    public ResponseEntity<JsonNode> current(Principal user, @PathVariable String kind, @PathVariable String id,
            @PathVariable String qid, @RequestParam(required = false) String targetId) {
        return response(service.current(user.getName(), kind, id, qid, targetId));
    }

    @GetMapping(BASE + "/tasks/{taskId}")
    public ResponseEntity<JsonNode> task(Principal user, @PathVariable String kind, @PathVariable String id,
            @PathVariable String qid, @PathVariable String taskId) {
        return response(service.task(user.getName(), kind, id, qid, taskId));
    }

    @PostMapping(value = BASE + "/tasks/{taskId}/retry", consumes = "application/json")
    public ResponseEntity<JsonNode> retry(Principal user, @PathVariable String kind, @PathVariable String id,
            @PathVariable String qid, @PathVariable String taskId, HttpServletRequest request) throws IOException {
        Retry body = body(request, Retry.class);
        return response(service.retry(user.getName(), kind, id, qid, taskId, body.requestId()));
    }

    @PostMapping(value = BASE + "/tasks/{taskId}/confirm", consumes = "application/json")
    public ResponseEntity<JsonNode> confirm(Principal user, @PathVariable String kind, @PathVariable String id,
            @PathVariable String qid, @PathVariable String taskId, HttpServletRequest request) throws IOException {
        Confirm body = body(request, Confirm.class);
        return response(service.confirm(user.getName(), kind, id, qid, taskId, body.requestId(), body.candidateVersion(), body.score()));
    }

    @GetMapping("/api/account/ai/settings")
    public ResponseEntity<Settings> settings(Principal user) { return response(new Settings(tasks.autoGrade(user.getName()))); }

    @PutMapping(value = "/api/account/ai/settings", consumes = "application/json")
    public Settings settings(Principal user, HttpServletRequest request) throws IOException {
        Settings body = body(request, Settings.class);
        tasks.setting(user.getName(), body.autoGrade(), clock.instant());
        return body;
    }

    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    private <T> T body(HttpServletRequest request, Class<T> type) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(8193);
        if (bytes.length > 8192 || request.getContentLengthLong() > 8192)
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE,"AI_REQUEST_TOO_LARGE","AI 评分请求超过 8 KiB 上限");
        T value;
        try { value = json.readValue(bytes, type); }
        catch (RuntimeException malformed) { throw invalid(); }
        if (value == null || !validator.validate(value).isEmpty()) throw invalid();
        return value;
    }

    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","AI 评分请求格式无效"); }
}
