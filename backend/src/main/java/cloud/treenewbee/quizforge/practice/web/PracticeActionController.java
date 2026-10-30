package cloud.treenewbee.quizforge.practice.web;

import cloud.treenewbee.quizforge.practice.application.DraftActionService;
import cloud.treenewbee.quizforge.practice.application.PracticeViews.DraftResponse;
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
    public PracticeActionController(DraftActionService drafts) { this.drafts = drafts; }

    @PostMapping("/api/collections/bank/{id}/questions/{qid}/actions")
    public ResponseEntity<DraftResponse> action(Principal principal, @PathVariable String id, @PathVariable String qid,
            @Valid @RequestBody Action body) {
        if (!body.revision().isIntegralNumber() || !body.revision().canConvertToLong() || body.revision().asLong() < 0
                || body.revision().asLong() == Long.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "revision 必须为有效的非负整数");
        }
        if (!"draft".equals(body.action())) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "FEATURE_NOT_AVAILABLE",
                    "whiteboard".equals(body.action()) ? "白板保存尚未实现" : "该操作尚未实现");
        }
        if (body.data() == null || !body.data().isObject() || !body.data().has("answer")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "草稿缺少 data.answer");
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(drafts.save(principal.getName(), id, qid, body.requestId(), body.revision().asLong(),
                        body.data().get("answer"), body.contentVersion()));
    }
}
