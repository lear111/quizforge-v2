package cloud.treenewbee.quizforge.admin.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;

/**
 * 管理后台的只读查询：用户列表与详情、审计日志。写操作沿用各模块已有的接口
 * （封禁 PUT /api/admin/users/{id}/block，存储额度 PUT /api/admin/users/{id}/quota）。
 * 管理员身份由 SecurityConfig 对 /api/admin/** 统一检查。
 */
@RestController
public class AdminConsoleController {

    /** 用户详情：账号信息 + 存储已用 / 额度。 */
    public record UserDetailResponse(AccountAdminService.UserDetail user, Storage storage) {
    }

    /**
     * overrideBytes 为空表示使用默认额度。PUT /api/admin/users/{id}/quota 同时设置两项额度，
     * 因此一并返回每日下载额度的覆盖值，修改存储额度时原样回传。
     */
    public record Storage(long usedBytes, long limitBytes, Long overrideBytes, Long dailyDownloadOverrideBytes) {
    }

    private final AccountAdminService accounts;
    private final QuotaService quotas;
    private final AdminAuditService audit;

    public AdminConsoleController(AccountAdminService accounts, QuotaService quotas, AdminAuditService audit) {
        this.accounts = accounts;
        this.quotas = quotas;
        this.audit = audit;
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<AccountAdminService.UserPage> users(@RequestParam(defaultValue = "") String q,
                                                              @RequestParam(required = false) String cursor,
                                                              @RequestParam(defaultValue = "20") int limit) {
        return noStore(accounts.search(q, cursor, limit));
    }

    @GetMapping("/api/admin/users/{id}")
    public ResponseEntity<UserDetailResponse> user(@PathVariable String id) {
        var detail = accounts.detail(id);
        var usage = quotas.usage(id);
        var storage = usage.storage();
        return noStore(new UserDetailResponse(detail, new Storage(storage.usedBytes(), storage.limitBytes(),
                storage.overrideBytes(), usage.dailyDownload().overrideBytes())));
    }

    @GetMapping("/api/admin/audit")
    public ResponseEntity<AdminAuditService.Page> audit(@RequestParam(required = false) String cursor,
                                                        @RequestParam(defaultValue = "50") int limit) {
        return noStore(audit.page(cursor, limit));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
