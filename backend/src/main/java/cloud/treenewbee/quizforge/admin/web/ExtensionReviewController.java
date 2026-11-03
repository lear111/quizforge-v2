package cloud.treenewbee.quizforge.admin.web;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.admin.application.ExtensionReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 后台拓展审核（白名单）。/api/admin/** 由 SecurityConfig 统一检查管理员。 */
@RestController
public class ExtensionReviewController {

    public record Revoke(@NotNull @Size(max = 1000) String reason) {
    }

    private final ExtensionReviewService reviews;

    public ExtensionReviewController(ExtensionReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/admin/extensions")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", reviews.list(status)));
    }

    /** 撤销前的影响范围：使用它的私人题库数量与广场题库包；以及同一拓展 ID 的其他版本。 */
    @GetMapping("/api/admin/extensions/{digest}/impact")
    public ResponseEntity<Map<String, Object>> impact(@PathVariable String digest) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("impact", reviews.impact(digest), "siblings", reviews.siblings(digest)));
    }

    @PostMapping("/api/admin/extensions/{digest}/approve")
    public ExtensionReviewService.Item approve(Principal actor, @PathVariable String digest) {
        return reviews.approve(actor.getName(), digest);
    }

    /** 待审核时为拒绝，已批准时为撤销；必须填写原因。 */
    @PostMapping("/api/admin/extensions/{digest}/revoke")
    public ExtensionReviewService.Item revoke(Principal actor, @PathVariable String digest, @Valid @RequestBody Revoke body) {
        return reviews.revoke(actor.getName(), digest, body.reason());
    }
}
