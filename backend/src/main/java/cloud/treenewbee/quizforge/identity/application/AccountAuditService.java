package cloud.treenewbee.quizforge.identity.application;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.identity.infrastructure.PolicyMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.json.JsonMapper;

/** 账号模块的管理审计。必须与被审计的修改在同一事务内写入：审计失败则修改回滚。 */
@Service
public class AccountAuditService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PolicyMapper mapper;
    private final Clock clock;

    public AccountAuditService(PolicyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** actorId 为 null 表示服务器命令行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actorId, String target, String action, Map<String, ?> detail) {
        mapper.audit(Ids.uuidV7(), actorId, target, action, JSON.writeValueAsString(detail), clock.instant());
    }
}
