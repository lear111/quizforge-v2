package cloud.treenewbee.quizforge.practice.web;

import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.practice.application.PracticeLifecycleService;
import cloud.treenewbee.quizforge.practice.application.PracticeSummaryService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;

@RestController
public class PracticeLifecycleController {
    public record Restart(@NotBlank @Size(max = 64) String requestId, JsonNode roundId,
            @NotBlank @Size(max = 64) String summaryVersion) {}
    private final PracticeLifecycleService lifecycle;
    private final PracticeSummaryService summaries;
    public PracticeLifecycleController(PracticeLifecycleService lifecycle, PracticeSummaryService summaries) {
        this.lifecycle = lifecycle; this.summaries = summaries;
    }

    @GetMapping("/api/collections/bank/{id}/summary")
    public ResponseEntity<JsonNode> summary(Principal principal, @PathVariable String id) {
        return noStore(summaries.summary(principal.getName(), id));
    }

    @PostMapping("/api/collections/bank/{id}/restart")
    public ResponseEntity<JsonNode> restart(Principal principal, @PathVariable String id, @Valid @RequestBody Restart body) {
        if (body.roundId() == null || (!body.roundId().isNull() && (!body.roundId().isString()
                || body.roundId().asString().length() > 36)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "缺少有效的 roundId（尚无轮次时为 null）");
        return noStore(lifecycle.restart(principal.getName(), id, body.requestId(),
                body.roundId().isNull() ? null : body.roundId().asString(), body.summaryVersion()));
    }

    private static ResponseEntity<JsonNode> noStore(JsonNode value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
}
