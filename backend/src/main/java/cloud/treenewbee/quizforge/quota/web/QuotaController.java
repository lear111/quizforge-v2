package cloud.treenewbee.quizforge.quota.web;

import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.quota.application.QuotaService;

@RestController
public class QuotaController {
    private final QuotaService quotas;
    public QuotaController(QuotaService quotas) { this.quotas = quotas; }
    public record Limits(@PositiveOrZero Long storageBytes, @PositiveOrZero Long dailyDownloadBytes) {}

    @GetMapping("/api/account/quota")
    public ResponseEntity<QuotaService.Usage> usage(Principal user) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(quotas.usage(user.getName()));
    }

    @PutMapping("/api/admin/users/{id}/quota")
    public ResponseEntity<QuotaService.Usage> limits(Principal actor, @PathVariable String id, @Valid @RequestBody Limits limits) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(quotas.adjust(actor.getName(), id, limits.storageBytes(), limits.dailyDownloadBytes()));
    }
}
