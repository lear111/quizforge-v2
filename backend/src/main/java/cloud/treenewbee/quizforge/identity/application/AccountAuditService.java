package cloud.treenewbee.quizforge.identity.application;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.application.AdminAuditService;

/** 账号模块的管理审计，写入统一的 admin_audit（module = account），与被审计的修改在同一事务内。 */
@Service
public class AccountAuditService {

    private final AdminAuditService audit;

    public AccountAuditService(AdminAuditService audit) {
        this.audit = audit;
    }

    /** actorId 为 null 表示服务器命令行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actorId, String target, String action, Map<String, ?> detail) {
        audit.record(actorId, "account", action, target, detail);
    }
}
