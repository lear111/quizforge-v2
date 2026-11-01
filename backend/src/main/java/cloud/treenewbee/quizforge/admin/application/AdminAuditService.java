package cloud.treenewbee.quizforge.admin.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.admin.infrastructure.AdminAuditMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.KeysetCursor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理员操作审计：所有模块的管理员写操作都通过这里写入 admin_audit。
 * 必须与被审计的修改在同一事务内：审计写入失败时修改一起回滚。
 */
@Service
public class AdminAuditService {

    public static final int MAX_PAGE = 50;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Actor(String id, String name) {
    }

    public record Entry(String id, Actor actor, String module, String action, String target, JsonNode detail,
                        java.time.Instant createdAt) {
    }

    public record Page(List<Entry> items, String nextCursor) {
    }

    private final AdminAuditMapper mapper;
    private final Clock clock;

    public AdminAuditService(AdminAuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** actorId 为 null 表示服务器命令行；detail 不得包含密码、验证码、完整邀请码等秘密。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actorId, String module, String action, String target, Map<String, ?> detail) {
        mapper.insert(Ids.uuidV7(), actorId, module, action, target, JSON.writeValueAsString(detail), clock.instant());
    }

    /** 审计日志的只读列表，按时间倒序，键集分页。 */
    public Page page(String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        KeysetCursor after = KeysetCursor.decode(cursor);
        var rows = mapper.page(after == null ? null : after.time(), after == null ? null : after.id(), size + 1);
        var items = rows.stream().limit(size).map(row -> new Entry(row.id(),
                row.actorId() == null ? null : new Actor(row.actorId(), row.actorName()), row.module(), row.action(),
                row.target(), JSON.readTree(row.detail()), row.createdAt())).toList();
        String next = rows.size() > size ? KeysetCursor.encode(items.getLast().createdAt(), items.getLast().id()) : null;
        return new Page(items, next);
    }
}
