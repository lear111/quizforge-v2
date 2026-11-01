package cloud.treenewbee.quizforge.practice.web;

import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.practice.application.PracticeHistoryService;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/collections/bank/{id}/history")
public class PracticeHistoryController {
    public record Annotation(@NotBlank @Size(max=64) String requestId,@NotNull @PositiveOrZero Long revision,@NotNull JsonNode notes) {}
    private final PracticeHistoryService history;
    public PracticeHistoryController(PracticeHistoryService history) { this.history=history; }
    @GetMapping public ResponseEntity<JsonNode> list(Principal user,@PathVariable String id) { return response(history.list(user.getName(),id)); }
    @GetMapping("/{historyId}") public ResponseEntity<JsonNode> detail(Principal user,@PathVariable String id,@PathVariable String historyId) throws java.io.IOException {
        return response(history.detail(user.getName(),id,historyId));
    }
    @GetMapping("/{historyId}/listening/{materialId}") public ResponseEntity<Void> listening(Principal user,@PathVariable String id,
            @PathVariable String historyId,@PathVariable String materialId) throws java.io.IOException {
        var file=history.listening(user.getName(),id,historyId,materialId);
        return ResponseEntity.ok().header("Content-Type",file.mime()).header("Cache-Control","private, no-cache")
            .header("X-Content-Type-Options","nosniff").header("X-Accel-Redirect",file.redirect()).build();
    }
    @PostMapping("/{historyId}/questions/{qid}/annotations") public ResponseEntity<JsonNode> annotate(Principal user,@PathVariable String id,@PathVariable String historyId,
            @PathVariable String qid,@Valid @RequestBody Annotation body) {
        return response(history.annotate(user.getName(),id,historyId,qid,body.requestId(),body.revision(),body.notes()));
    }
    @DeleteMapping("/{historyId}") public ResponseEntity<Void> delete(Principal user,@PathVariable String id,@PathVariable String historyId) {
        history.delete(user.getName(),id,historyId);return ResponseEntity.noContent().build();
    }
    private static ResponseEntity<JsonNode> response(JsonNode value) {return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(value);}
}
