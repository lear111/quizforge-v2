package cloud.treenewbee.quizforge.practice.web;

import cloud.treenewbee.quizforge.practice.application.DraftActionService;
import cloud.treenewbee.quizforge.practice.application.SubmitActionService;
import cloud.treenewbee.quizforge.practice.application.PracticeLifecycleService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class PracticeActionController {
    public record Action(@NotBlank @Size(max = 64) String requestId, @NotNull JsonNode revision,
            @NotBlank String action, JsonNode data, String contentVersion) {}

    private final DraftActionService drafts;
    private final SubmitActionService submits;
    private final PracticeLifecycleService lifecycle;
    private final cloud.treenewbee.quizforge.practice.resources.AnswerResources resources;
    private final cloud.treenewbee.quizforge.practice.whiteboard.WhiteboardService whiteboards;
    public PracticeActionController(DraftActionService drafts, SubmitActionService submits, PracticeLifecycleService lifecycle,
            cloud.treenewbee.quizforge.practice.resources.AnswerResources resources,
            cloud.treenewbee.quizforge.practice.whiteboard.WhiteboardService whiteboards) {
        this.drafts = drafts; this.submits = submits; this.lifecycle = lifecycle;
        this.resources = resources;
        this.whiteboards = whiteboards;
    }

    @PostMapping("/api/collections/bank/{id}/questions/{qid}/actions")
    public ResponseEntity<?> action(Principal principal, @PathVariable String id, @PathVariable String qid,
            @Valid @RequestBody Action body) {
        if (!body.revision().isIntegralNumber() || !body.revision().canConvertToLong() || body.revision().asLong() < 0
                || body.revision().asLong() > Long.MAX_VALUE - ("submit".equals(body.action()) ? 2 : 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "revision 必须为有效的非负整数");
        }
        if ("whiteboard".equals(body.action())) return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(whiteboards.save(principal.getName(),id,qid,body.requestId(),body.revision().asLong(),body.data(),body.contentVersion()));
        if ("retry".equals(body.action()) || "review".equals(body.action())) {
            if (body.data() == null || !body.data().isObject()) throw invalid("操作缺少 data 对象");
            if ("review".equals(body.action()) && (!body.data().path("review").isObject()
                    || !body.data().path("review").path("score").isNumber()
                    || !Double.isFinite(body.data().path("review").path("score").doubleValue())))
                throw invalid("操作缺少有效的 data.review.score");
            var value = "retry".equals(body.action())
                    ? lifecycle.retry(principal.getName(), id, qid, body.requestId(), body.revision().asLong(), body.data(), body.contentVersion())
                    : lifecycle.review(principal.getName(), id, qid, body.requestId(), body.revision().asLong(), body.data(), body.contentVersion());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
        }
        if (!"draft".equals(body.action()) && !"submit".equals(body.action())) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "FEATURE_NOT_AVAILABLE",
                    "该操作尚未实现");
        }
        if (body.data() == null || !body.data().isObject() || !body.data().has("answer")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "操作缺少 data.answer");
        }
        resources.validate(principal.getName(), body.data().get("answer"));
        if ("submit".equals(body.action())) return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(submits.submit(principal.getName(), id, qid, body.requestId(), body.revision().asLong(),
                        body.data().get("answer"), body.contentVersion()));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(drafts.save(principal.getName(), id, qid, body.requestId(), body.revision().asLong(),
                        body.data().get("answer"), body.contentVersion()));
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message); }
}
