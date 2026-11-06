package cloud.treenewbee.quizforge.catalog.web;

import cloud.treenewbee.quizforge.catalog.application.PackFeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PackFeedbackController {
    public record Comment(String body) {}
    public record Report(String reason) {}
    public record Decision(@Size(max = 16) String decision, String note) {}
    private final PackFeedbackService feedback;
    public PackFeedbackController(PackFeedbackService feedback) { this.feedback = feedback; }

    @GetMapping("/api/plaza/packs/{id}/comments")
    public Object comments(Principal user, @PathVariable String id, @RequestParam(required = false) String cursor) {
        return feedback.comments(user == null ? null : user.getName(), id, cursor);
    }

    @PostMapping("/api/plaza/packs/{id}/comments")
    public ResponseEntity<?> comment(Principal user, @PathVariable String id, @Valid @RequestBody Comment body) {
        return ResponseEntity.status(201).body(Map.of("comment", feedback.comment(user.getName(), id, body.body())));
    }

    @DeleteMapping("/api/plaza/comments/{id}")
    public ResponseEntity<Void> delete(Principal user, @PathVariable String id) {
        feedback.deleteComment(user.getName(), id, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/plaza/packs/{id}/report")
    public ResponseEntity<?> report(Principal user, @PathVariable String id, @Valid @RequestBody Report body) {
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", feedback.report(user.getName(), id, body.reason())));
    }

    @GetMapping("/api/admin/catalog/reports")
    public Object reports(Principal user, @RequestParam(required = false) String cursor) {
        return feedback.reports(user.getName(), cursor);
    }

    @GetMapping("/api/admin/catalog/reports/{id}")
    public Object detail(Principal user, @PathVariable String id) { return feedback.reportDetail(user.getName(), id); }

    @PostMapping("/api/admin/catalog/reports/{id}/resolve")
    public Object resolve(Principal user, @PathVariable String id, @Valid @RequestBody Decision body) {
        return feedback.resolve(user.getName(), id, body.decision(), body.note());
    }

    @DeleteMapping("/api/admin/catalog/comments/{id}")
    public ResponseEntity<Void> adminDelete(Principal user, @PathVariable String id) {
        feedback.deleteComment(user.getName(), id, true);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/catalog/comments")
    public Object adminComments(Principal user, @RequestParam(required = false) String cursor) {
        return feedback.adminComments(user.getName(), cursor);
    }
}
