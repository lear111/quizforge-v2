package cloud.treenewbee.quizforge.identity.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.identity.application.AuthPolicyService;
import cloud.treenewbee.quizforge.identity.application.InvitationService;
import jakarta.validation.Valid;

/**
 * 管理员接口：登录与注册设置、邀请码、封禁。界面随管理后台另做。
 * 每个请求都按会话用户重新检查管理员身份。
 */
@RestController
public class AdminAccountController {

    private final AccountAdminService admins;
    private final AuthPolicyService policies;
    private final InvitationService invitations;

    public AdminAccountController(AccountAdminService admins, AuthPolicyService policies,
                                  InvitationService invitations) {
        this.admins = admins;
        this.policies = policies;
        this.invitations = invitations;
    }

    @GetMapping("/api/admin/authentication")
    public Map<String, Object> policy(Principal principal) {
        admins.requireAdmin(principal.getName());
        return Map.of("policy", policies.current(), "capabilities", policies.capabilities());
    }

    @PutMapping("/api/admin/authentication")
    public Map<String, Object> updatePolicy(@Valid @RequestBody AuthRequests.PolicyUpdate body, Principal principal) {
        admins.requireAdmin(principal.getName());
        var change = new AuthPolicyService.Change(body.loginCodeRequired(), body.loginCaptchaRequired(),
                body.registrationCodeRequired(), body.registrationCaptchaRequired(), body.invitationRequired());
        var saved = policies.update(principal.getName(), change, body.revision());
        return Map.of("policy", saved, "capabilities", policies.capabilities());
    }

    @GetMapping("/api/admin/invitations")
    public Map<String, Object> invitations(Principal principal) {
        admins.requireAdmin(principal.getName());
        return Map.of("items", invitations.list(), "invitationRequired", policies.current().invitationRequired());
    }

    /** 生成邀请码；完整码只在本次响应中返回（前端可“导出本批次”）。 */
    @PostMapping("/api/admin/invitations")
    public ResponseEntity<Map<String, List<InvitationService.Issued>>> createInvitations(
            @Valid @RequestBody AuthRequests.InvitationCreate body, Principal principal) {
        admins.requireAdmin(principal.getName());
        int count = body.count() == null ? 1 : body.count();
        int days = body.days() == null ? 30 : body.days();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("items", invitations.create(principal.getName(), count, days)));
    }

    @PostMapping("/api/admin/invitations/{id}/revoke")
    public InvitationService.Item revokeInvitation(@PathVariable String id, Principal principal) {
        admins.requireAdmin(principal.getName());
        return invitations.revoke(principal.getName(), id);
    }

    @PutMapping("/api/admin/users/{id}/block")
    public Map<String, Object> block(@PathVariable String id, @Valid @RequestBody AuthRequests.Block body,
                                     Principal principal) {
        admins.requireAdmin(principal.getName());
        admins.setBlocked(principal.getName(), id, body.blocked(), body.reason());
        return Map.of("ok", true, "blocked", body.blocked());
    }
}
