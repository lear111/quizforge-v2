package cloud.treenewbee.quizforge.notification.web;

import java.security.Principal;
import java.util.Map;

import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.notification.application.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 用户的通知接口与管理员的通知接口（/api/admin/** 由 SecurityConfig 统一检查管理员）。用户身份只来自会话。 */
@RestController
public class NotificationController {

    public record Publish(
            @NotBlank @CodePointLength(max = NotificationService.TITLE_MAX) String title,
            @NotBlank @Size(max = NotificationService.BODY_MAX * 2) String body,
            @NotNull @Pattern(regexp = "all|user") String audience,
            @Size(max = 36) String userId,
            Boolean pinned) {
    }

    public record Pin(@NotNull Boolean pinned) {
    }

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- 用户

    @GetMapping("/api/notifications/status")
    public ResponseEntity<Map<String, Boolean>> status(Principal user) {
        return noStore(Map.of("unseen", notifications.hasUnseen(user.getName())));
    }

    @GetMapping("/api/notifications")
    public ResponseEntity<NotificationService.UserList> list(Principal user) {
        return noStore(notifications.list(user.getName()));
    }

    @GetMapping("/api/notifications/{id}")
    public ResponseEntity<NotificationService.Detail> detail(Principal user, @PathVariable String id) {
        return noStore(notifications.detail(user.getName(), id));
    }

    @PostMapping("/api/notifications/seen")
    public ResponseEntity<Void> seen(Principal user) {
        notifications.markSeen(user.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/notifications/{id}/read")
    public ResponseEntity<Void> read(Principal user, @PathVariable String id) {
        notifications.markRead(user.getName(), id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- 管理员：新建、查看、删除、置顶（不能修改）

    @GetMapping("/api/admin/notifications")
    public ResponseEntity<NotificationService.AdminPage> adminList(@RequestParam(required = false) String cursor,
                                                                   @RequestParam(defaultValue = "50") int limit) {
        return noStore(notifications.adminPage(cursor, limit));
    }

    @PostMapping("/api/admin/notifications")
    public ResponseEntity<Map<String, String>> publish(Principal actor, @Valid @RequestBody Publish body) {
        var audience = NotificationService.Audience.valueOf(body.audience().toUpperCase());
        String id = notifications.publishByAdmin(actor.getName(), audience, body.userId(), body.title(), body.body(),
                Boolean.TRUE.equals(body.pinned()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/api/admin/notifications/{id}/pin")
    public ResponseEntity<Void> pin(Principal actor, @PathVariable String id, @Valid @RequestBody Pin body) {
        notifications.pin(actor.getName(), id, body.pinned());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/admin/notifications/{id}")
    public ResponseEntity<Void> delete(Principal actor, @PathVariable String id) {
        notifications.delete(actor.getName(), id);
        return ResponseEntity.noContent().build();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
