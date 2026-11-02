package cloud.treenewbee.quizforge.catalog.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.catalog.application.CatalogReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 后台题库包审核。/api/admin/** 由 SecurityConfig 统一检查管理员。 */
@RestController
public class CatalogReviewController {

    public record Reject(@NotNull @Size(max = 1000) String reason) {
    }

    private final CatalogReviewService reviews;

    public CatalogReviewController(CatalogReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/admin/catalog/reviews")
    public ResponseEntity<Map<String, Object>> pending() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", reviews.pending()));
    }

    @GetMapping("/api/admin/catalog/packs/{id}/revisions/{revision}")
    public ResponseEntity<CatalogReviewService.Detail> detail(@PathVariable String id, @PathVariable int revision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.detail(id, revision));
    }

    /** 管理员下载该版本的 .qbank 检查拓展代码。 */
    @GetMapping("/api/admin/catalog/packs/{id}/revisions/{revision}/archive")
    public ResponseEntity<Void> archive(@PathVariable String id, @PathVariable int revision) {
        var detail = reviews.detail(id, revision);
        String sha = detail.sha256();
        String name = URLEncoder.encode(detail.revision().filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"pack.qbank\"; filename*=UTF-8''" + name)
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .header("X-Accel-Redirect", "/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha).build();
    }

    @PostMapping("/api/admin/catalog/packs/{id}/revisions/{revision}/approve")
    public ResponseEntity<Void> approve(Principal actor, @PathVariable String id, @PathVariable int revision) {
        reviews.approve(actor.getName(), id, revision);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/catalog/packs/{id}/revisions/{revision}/reject")
    public ResponseEntity<Void> reject(Principal actor, @PathVariable String id, @PathVariable int revision,
                                       @Valid @RequestBody Reject body) {
        reviews.reject(actor.getName(), id, revision, body.reason());
        return ResponseEntity.noContent().build();
    }
}
