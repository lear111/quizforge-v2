package cloud.treenewbee.quizforge.practice.web;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.practice.application.PracticeViews.Catalog;
import cloud.treenewbee.quizforge.practice.application.PracticeViews.Overview;

@RestController
public class PracticeReadController {
    private final PracticeReadService reads;
    public PracticeReadController(PracticeReadService reads) { this.reads = reads; }

    @GetMapping({"/api/catalog", "/api/library/index"})
    public ResponseEntity<Catalog> catalog(Principal principal) { return noStore(reads.catalog(principal.getName())); }

    @GetMapping("/api/collections/bank/{id}")
    public ResponseEntity<Overview> overview(Principal principal, @PathVariable String id) {
        return noStore(reads.overview(principal.getName(), id));
    }

    @GetMapping("/api/collections/bank/{id}/questions/{qid}")
    public ResponseEntity<Object> question(Principal principal, @PathVariable String id, @PathVariable String qid,
            @RequestHeader(value = "X-QuizForge-Question-Stamp", required = false) String stamp) {
        return noStore(reads.question(principal.getName(), id, qid, stamp));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @GetMapping("/api/collections/bank/{id}/listening/{materialId}")
    public ResponseEntity<Void> listening(Principal principal, @PathVariable String id, @PathVariable String materialId) throws java.io.IOException {
        var file = reads.listening(principal.getName(), id, materialId);
        return ResponseEntity.ok().header("Content-Type", file.mime()).header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").header("X-Accel-Redirect", file.redirect()).build();
    }
}
