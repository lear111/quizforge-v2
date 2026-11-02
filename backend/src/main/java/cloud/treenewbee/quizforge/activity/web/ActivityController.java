package cloud.treenewbee.quizforge.activity.web;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.activity.application.ActivityTracker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 网页的活跃上报（与旧平台 POST /api/activity 相同：{kind: visit | practice | heartbeat}）。需要登录。 */
@RestController
public class ActivityController {

    public record Activity(@NotNull @Pattern(regexp = "visit|practice|heartbeat") String kind) {
    }

    private final ActivityTracker tracker;

    public ActivityController(ActivityTracker tracker) {
        this.tracker = tracker;
    }

    @PostMapping("/api/activity")
    public ResponseEntity<Void> activity(Principal user, @Valid @RequestBody Activity body) {
        tracker.record(user.getName(), body.kind());
        return ResponseEntity.noContent().build();
    }
}
