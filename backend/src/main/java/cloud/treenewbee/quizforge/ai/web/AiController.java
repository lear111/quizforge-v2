package cloud.treenewbee.quizforge.ai.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.ai.application.AiBalanceService;
import cloud.treenewbee.quizforge.ai.application.RedeemCodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * AI 余额与兑换码。用户接口只读写会话用户自己的数据；/api/admin/** 由 SecurityConfig 统一检查管理员。
 */
@RestController
public class AiController {

    public record Redeem(@NotNull @Size(max = 64) String code) {
    }

    public record Adjust(@NotNull Long delta, @NotNull @Size(max = 400) String reason) {
    }

    public record CreateCodes(@NotNull @Min(1) @Max(200) Integer count, @NotNull Long points,
                              @NotNull @Min(1) @Max(3650) Integer days, @Size(max = 120) String note) {
    }

    /** 用户看到的流水：不含操作的管理员。 */
    public record UserEntry(long seq, long delta, long balanceAfter, String kind, String reason,
                            java.time.Instant createdAt) {
    }

    public record UserLedger(List<UserEntry> items, Long nextBefore) {
    }

    private final AiBalanceService balances;
    private final RedeemCodeService codes;

    public AiController(AiBalanceService balances, RedeemCodeService codes) {
        this.balances = balances;
        this.codes = codes;
    }

    // ---------------------------------------------------------------- 用户

    @GetMapping("/api/account/ai")
    public ResponseEntity<AiBalanceService.Balance> balance(Principal user) {
        return noStore(balances.balance(user.getName()));
    }

    @GetMapping("/api/account/ai/ledger")
    public ResponseEntity<UserLedger> ledger(Principal user, @RequestParam(required = false) Long before,
                                             @RequestParam(defaultValue = "20") int limit) {
        var page = balances.ledger(user.getName(), before, limit);
        return noStore(new UserLedger(page.items().stream().map(entry -> new UserEntry(entry.seq(), entry.delta(),
                entry.balanceAfter(), entry.kind(), entry.reason(), entry.createdAt())).toList(), page.nextBefore()));
    }

    @PostMapping("/api/account/ai/redeem")
    public RedeemCodeService.Redeemed redeem(Principal user, @Valid @RequestBody Redeem body) {
        return codes.redeem(user.getName(), body.code());
    }

    // ---------------------------------------------------------------- 管理员

    @GetMapping("/api/admin/users/{id}/ai")
    public ResponseEntity<Map<String, Object>> userAi(@PathVariable String id, @RequestParam(required = false) Long before) {
        return noStore(Map.of("balance", balances.balance(id), "ledger", balances.ledger(id, before, 20)));
    }

    @PostMapping("/api/admin/users/{id}/ai-balance")
    public AiBalanceService.Entry adjust(Principal actor, @PathVariable String id, @Valid @RequestBody Adjust body) {
        return balances.adjust(actor.getName(), id, body.delta(), body.reason());
    }

    @GetMapping("/api/admin/ai-codes")
    public ResponseEntity<RedeemCodeService.Page> codes(@RequestParam(required = false) Long before,
                                                        @RequestParam(defaultValue = "50") int limit) {
        return noStore(codes.page(before, limit));
    }

    /** 生成兑换码；完整码只在本次响应中返回。 */
    @PostMapping("/api/admin/ai-codes")
    public ResponseEntity<Map<String, List<RedeemCodeService.Issued>>> create(Principal actor,
                                                                              @Valid @RequestBody CreateCodes body) {
        var issued = codes.create(actor.getName(), body.count(), body.points(), body.days(), body.note());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(Map.of("items", issued));
    }

    @PostMapping("/api/admin/ai-codes/{id}/revoke")
    public RedeemCodeService.Item revoke(Principal actor, @PathVariable String id) {
        return codes.revoke(actor.getName(), id);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
