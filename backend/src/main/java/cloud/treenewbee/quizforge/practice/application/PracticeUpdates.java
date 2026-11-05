package cloud.treenewbee.quizforge.practice.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.practice.application.PublishedVersions.Latest;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeUpdateMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeUpdateMapper.Source;

/**
 * 广场题库实例的懒更新（设计 04 第 3 节，需求 FR-10）：
 * <ul>
 *   <li>打开题库概览时，比较实例的 source_revision 与该包当前已审核版本，按 source_bank_uuid 找到新版本中的题库；</li>
 *   <li>没有进行中的作答 → 本次请求中切换（bank_id、source_revision，progress_version + 1）；</li>
 *   <li>有进行中的作答 → 不切换，返回 {@code update:{status:"pending", revision}}；“再练一次”在清空作答的同一事务中切换；</li>
 *   <li>新版本中没有这本题库 → 标记“作者已移除”，停在当前版本；整个包被删除 → “作者已删除”。练习照常可用。</li>
 * </ul>
 * 切换只修改实例指向的内容，不调用 rule-runner，不碰冻结的历史、标题与分组。加锁顺序 context → collection（与其他写操作一致，见 PracticeUpdates.open）。
 */
@Service
public class PracticeUpdates {

    /** 返回给前端的更新状态：current 已是最新；pending 有新版本，再练一次时使用；removed 作者已移除；deleted 作者已删除。 */
    public record Update(String status, Integer revision) {
        static final Update CURRENT = new Update("current", null);
        static final Update REMOVED = new Update("removed", null);
        static final Update DELETED = new Update("deleted", null);
    }

    private enum Plan { NONE, SWITCH, REMOVE }

    private record Decision(Plan plan, Update update, Integer revision, String bankId) {
    }

    private final PracticeUpdateMapper mapper;
    private final PracticeContextMapper contexts;
    private final PublishedVersions versions;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PracticeUpdates(PracticeUpdateMapper mapper, PracticeContextMapper contexts, PublishedVersions versions,
                           TransactionTemplate tx, Clock clock) {
        this.mapper = mapper;
        this.contexts = contexts;
        this.versions = versions;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 打开题库概览时调用：能切换时在本次请求中完成切换。不是来自广场的实例返回 null。
     * 先不加锁判断，只有确实有新版本时才开启事务、加锁并再次确认。
     */
    public Update open(String userId, String collectionId) {
        Source source = mapper.source(userId, collectionId).orElse(null);
        if (source == null || source.sourcePackId() == null) return null;
        Decision first = decide(source, latest(source));
        if (first.plan() == Plan.NONE) return first.update();
        // 有进行中的作答时每次打开都不加锁直接返回“有新版本”；可以切换时才加锁并在锁内再次确认。
        if (first.plan() == Plan.SWITCH && !first.bankId().equals(source.bankId())
                && inProgress(collectionId, mapper.practiceContext(collectionId, userId).orElse(null), userId)) {
            return first.update();
        }
        // 存档 ID 在事务外读取：事务中的第一条语句必须是加锁读。REPEATABLE READ 下一致读在第一次执行时建立快照，
        // 若先做普通查询再等锁，之后“是否有进行中的作答”会读到等锁之前的快照，看不到刚提交的第一次保存。
        // 存档不存在时只锁实例：之后才创建存档并保存的请求，在读取实例确认题目版本时会等到本次切换提交。
        String context = mapper.practiceContext(collectionId, userId).orElse(null);
        return tx.execute(status -> {
            // 加锁顺序 context → collection：与保存、提交、完成练习等写操作一致（它们先锁存档，草稿保存再在同一条 SQL 中
            // 读取题库实例确认题目版本）。反过来先锁实例会与第一次保存形成死锁。
            if (context != null) contexts.lockForUpdate(context, userId).orElseThrow();
            Source locked = mapper.lockSource(userId, collectionId).orElse(null);
            if (locked == null || locked.sourcePackId() == null) return null;
            Decision decision = decide(locked, latest(locked));
            switch (decision.plan()) {
                case REMOVE -> {
                    mapper.markRemoved(locked.id(), userId, locked.sourceRevision());
                    return Update.REMOVED;
                }
                case SWITCH -> {
                    // 再次确认没有进行中的作答（锁住存档之后的一致读，看得到所有已提交的保存）。
                    // 新版本中这本题库的内容没有变化（同一份共享内容）时，直接推进版本号，不影响作答。
                    if (!decision.bankId().equals(locked.bankId()) && inProgress(collectionId, context, userId)) {
                        return new Update("pending", decision.revision());
                    }
                    apply(locked, decision, context, userId);
                    return Update.CURRENT;
                }
                default -> {
                    return decision.update();
                }
            }
        });
    }

    /**
     * “再练一次”清空作答的同一事务中调用：调用方已锁住存档并清空了作答，这里再锁题库实例（context → collection）。
     * 有进行中或评分中的考试时不切换（下次打开时再切换）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void afterRestart(String userId, String collectionId, String contextId) {
        Source locked = mapper.lockSource(userId, collectionId).orElse(null);
        if (locked == null || locked.sourcePackId() == null) return;
        Decision decision = decide(locked, latest(locked));
        if (decision.plan() == Plan.REMOVE) mapper.markRemoved(locked.id(), userId, locked.sourceRevision());
        if (decision.plan() != Plan.SWITCH) return;
        if (!decision.bankId().equals(locked.bankId()) && mapper.examInProgress(collectionId, userId)) return;
        apply(locked, decision, contextId, userId);
    }

    /** 练习空间题库列表：每个来自广场的实例的更新状态（一次批量查询，不切换）。 */
    public Map<String, Update> list(String userId) {
        List<Source> sources = mapper.sources(userId);
        Map<String, Update> result = new LinkedHashMap<>();
        if (sources.isEmpty()) return result;
        Set<String> packs = sources.stream().map(Source::sourcePackId).collect(Collectors.toSet());
        Set<String> uuids = sources.stream().map(Source::sourceBankUuid).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, Latest> latest = versions.latest(packs, uuids);
        for (Source source : sources) {
            Decision decision = decide(source, latest.get(source.sourcePackId()));
            // 列表只读：可以切换的实例在下次打开时切换，这里显示为“有新版本”（内容未变的除外）。
            result.put(source.id(), switch (decision.plan()) {
                case SWITCH -> decision.bankId().equals(source.bankId()) ? Update.CURRENT : new Update("pending", decision.revision());
                case REMOVE -> Update.REMOVED;
                default -> decision.update();
            });
        }
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private Latest latest(Source source) {
        return versions.latest(Set.of(source.sourcePackId()),
                source.sourceBankUuid() == null ? Set.of() : Set.of(source.sourceBankUuid())).get(source.sourcePackId());
    }

    /** 只看已审核版本；包删除、作者移除优先于版本比较。 */
    private static Decision decide(Source source, Latest latest) {
        if ("removed".equals(source.updateStatus())) return new Decision(Plan.NONE, Update.REMOVED, null, null);
        if (latest == null || latest.deleted()) return new Decision(Plan.NONE, Update.DELETED, null, null);
        if (source.sourceRevision() == null) return new Decision(Plan.NONE, Update.CURRENT, null, null);
        Integer approved = latest.approvedRevision();
        int current = source.sourceRevision();
        if (approved == null || approved <= current) return new Decision(Plan.NONE, Update.CURRENT, null, null);
        String bankId = source.sourceBankUuid() == null ? null : latest.banks().get(source.sourceBankUuid());
        if (bankId == null) return new Decision(Plan.REMOVE, Update.REMOVED, approved, null);
        return new Decision(Plan.SWITCH, new Update("pending", approved), approved, bankId);
    }

    private boolean inProgress(String collectionId, String contextId, String userId) {
        return contextId != null && mapper.practiceInProgress(contextId, userId) || mapper.examInProgress(collectionId, userId);
    }

    private void apply(Source locked, Decision decision, String contextId, String userId) {
        if (mapper.switchTo(locked.id(), userId, decision.bankId(), decision.revision(), locked.sourceRevision()) != 1) {
            throw new IllegalStateException("题库实例在锁内发生变化");
        }
        if (contextId != null) mapper.bump(contextId, userId, clock.instant());
    }
}
