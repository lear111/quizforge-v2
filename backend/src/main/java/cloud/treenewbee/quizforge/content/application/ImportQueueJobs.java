package cloud.treenewbee.quizforge.content.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.admin.application.ExtensionReviewService;
import cloud.treenewbee.quizforge.admin.infrastructure.ExtensionReviewMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper.PackBankRow;
import cloud.treenewbee.quizforge.content.archive.ArchiveRejectedException;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.domain.PackageRejectedException;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.content.infrastructure.ImportCatalogMapper;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper.Job;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.notification.application.NotificationService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.jobs.DatabaseJobPoller;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/** 和练习任务共用退避调度；数据库槽位、租约与代次使多实例与重启后的提交有界。 */
@Service
public class ImportQueueJobs {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ImportQueueJobs.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final String owner = UUID.randomUUID().toString();
    private final ImportQueueMapper jobs;
    private final ImportCatalogMapper metadata;
    private final CatalogMapper catalog;
    private final ImportService imports;
    private final BlobStore blobs;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ExtensionReviewService reviews;
    private final ExtensionReviewMapper reviewRows;
    private final AccountAdminService admins;
    private final AdminAuditService audit;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final cloud.treenewbee.quizforge.rules.RuleRunnerClient runner;
    private final boolean enabled;
    private final int concurrency;
    private final ConcurrentHashMap<String, Job> active = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private ScheduledExecutorService heartbeat;
    private DatabaseJobPoller poller;
    private String reconcileAfter;
    private int reconcileRevision;

    public ImportQueueJobs(ImportQueueMapper jobs, ImportCatalogMapper metadata, CatalogMapper catalog,
            ImportService imports, BlobStore blobs, TransactionTemplate tx, Clock clock,
            ExtensionReviewService reviews, ExtensionReviewMapper reviewRows, AccountAdminService admins,
            AdminAuditService audit, NotificationService notifications, JsonMapper json,
            cloud.treenewbee.quizforge.rules.RuleRunnerClient runner,
            @Value("${qf.import.jobs.enabled:true}") boolean enabled,
            @Value("${qf.import.jobs.concurrency:1}") int concurrency) {
        if (concurrency < 1 || concurrency > 16) throw new IllegalArgumentException("导入任务并发范围为 1..16");
        this.jobs = jobs;
        this.metadata = metadata;
        this.catalog = catalog;
        this.imports = imports;
        this.blobs = blobs;
        this.tx = tx;
        this.clock = clock;
        this.reviews = reviews;
        this.reviewRows = reviewRows;
        this.admins = admins;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
        this.runner = runner;
        this.enabled = enabled;
        this.concurrency = concurrency;
    }

    @PostConstruct
    void start() {
        for (int i = 0; i < concurrency; i++) jobs.ensureSlot(i);
        if (!enabled) return;
        heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("import-leases").factory());
        heartbeat.scheduleWithFixedDelay(this::renewAll, 20, 20, TimeUnit.SECONDS);
        poller = new DatabaseJobPoller(Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("import-queue").factory()), this::tick, 250);
        poller.start();
    }

    @PreDestroy
    void stop() {
        if (poller != null) poller.close();
        if (heartbeat != null) heartbeat.shutdownNow();
        workers.shutdownNow();
    }

    /** 事务提交事件立即唤醒本实例；其他实例最多等一次空闲退避。 */
    @org.springframework.transaction.event.TransactionalEventListener
    public void uploaded(ImportUploadService.Accepted event) {
        if (poller != null) poller.wake();
    }

    boolean tick() {
        if (runner.foregroundRecentlyActive()) return false;
        Job job = claim();
        if (job == null) {
            reconcileReviews();
            return false;
        }
        active.put(job.id(), job);
        workers.submit(() -> {
            try { process(job); }
            finally {
                active.remove(job.id());
                if (poller != null) poller.wake();
            }
        });
        return true;
    }

    /** 测试与维护可推进一个任务，不使用固定等待。 */
    public boolean runOne() {
        Job job = claim();
        if (job == null) return false;
        process(job);
        return true;
    }

    public Job claim() {
        Instant now = now();
        recoverExpired(now);
        metadata.failedJobs();
        return tx.execute(status -> {
            var slot = jobs.slot(concurrency, now);
            if (slot.isEmpty()) return null;
            var candidate = jobs.next(now);
            if (candidate.isEmpty()) return null;
            Job job = candidate.get();
            if (jobs.claim(job.id(), owner, now.plus(LEASE), now) != 1) return null;
            jobs.occupy(slot.get().id(), job.id(), job.generation() + 1, now.plus(LEASE));
            return jobs.find(job.id()).orElseThrow();
        });
    }

    /** 每个候选独立短事务；忙碌的行留到下一轮，租约条件在锁内复核。 */
    public int recoverExpired(Instant now) {
        int recovered = 0;
        for (String id : jobs.expired(now)) {
            recovered += tx.execute(status -> jobs.lockExpired(id, now).isPresent() ? jobs.recover(id, now) : 0);
        }
        return recovered;
    }

    private void renewAll() {
        for (Job job : active.values()) {
            try {
                tx.executeWithoutResult(status -> {
                    Instant now = now();
                    if (jobs.renew(job.id(), owner, job.generation(), now.plus(LEASE), now) == 1) {
                        jobs.renewSlot(job.id(), job.generation(), now.plus(LEASE));
                    }
                });
            } catch (RuntimeException error) {
                LOG.warn("导入任务续租失败：job={}", job.id(), error);
            }
        }
    }

    public void process(Job job) {
        Path temporary = null;
        try {
            current(job);
            var revision = catalog.revision(job.packId(), job.revision()).orElseThrow(StaleJob::new);
            temporary = Files.createTempFile("qf-validation-", ".qbank");
            try (var input = blobs.open(HexFormat.of().formatHex(revision.blobSha256()))) {
                Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            imports.ingestQueued(temporary, () -> current(job), group -> {
                current(job);
                fence(job);
            }, ingested -> {
                List<PackBankRow> banks = new java.util.ArrayList<>();
                for (int i = 0; i < ingested.banks().size(); i++) {
                    var bank = ingested.banks().get(i);
                    banks.add(new PackBankRow(bank.bankUuid(), bank.bankId(), bank.title(), i, bank.questionCount(), null));
                }
                catalog.insertBanks(job.packId(), job.revision(), banks);
                metadata.digests(job.packId(), job.revision(), json.writeValueAsString(ingested.extensionDigests()));
                metadata.validation(job.packId(), job.revision(), "validated", null);
                var pack = catalog.pack(job.packId()).orElseThrow(StaleJob::new);
                if (admins.isAdmin(pack.ownerId())) {
                    for (String digest : ingested.extensionDigests().stream().sorted().toList()) {
                        if (reviewRows.decide(HexFormat.of().parseHex(digest), List.of("pending"), "approved",
                                pack.ownerId(), null, now()) == 1) {
                            audit.record(pack.ownerId(), "admin", "extension-auto-approved", digest,
                                    Map.of("说明", "管理员上传自动批准", "packId", pack.id(), "revision", job.revision()));
                        }
                    }
                }
                decideValidated(pack.ownerId(), job.packId(), job.revision(), ingested.extensionDigests(), admins.isAdmin(pack.ownerId()));
                complete(job, "completed", null, now());
                return null;
            });
        } catch (StaleJob ignored) {
            // 删除、重新上传或租约失效，丢弃结果。
        } catch (cloud.treenewbee.quizforge.rules.RuleRunnerClient.BackgroundBusy busy) {
            tx.executeWithoutResult(status -> jobs.yield(job.id(), owner, job.generation(), now().plusMillis(250), now()));
        } catch (ImportFailure failure) {
            fail(job, failure.code(), failure.getMessage(), List.of("RULE_UNAVAILABLE", "RULE_TIMEOUT").contains(failure.code()));
        } catch (PackageRejectedException failure) {
            String reason = failure.code() == PackageRejectedException.Code.UNSUPPORTED_LIBRARY_ARCHIVE
                    && failure.getMessage().contains("旧版") ? "旧格式，请用 Windows 版或转换工具升级" : failure.getMessage();
            fail(job, failure.code().name(), reason, false);
        } catch (ArchiveRejectedException failure) {
            fail(job, failure.reason() == ArchiveRejectedException.Reason.LIMIT ? "LIBRARY_ARCHIVE_LIMIT" : "INVALID_LIBRARY_ARCHIVE",
                    "题库包无法安全解压，请检查文件是否完整", false);
        } catch (Exception error) {
            LOG.warn("导入后台处理失败：job={}", job.id(), error);
            fail(job, "PROCESSING_FAILED", "处理失败，请联系管理员", true);
        } finally {
            try {
                jobs.release(job.id(), job.generation());
            } finally {
                // 数据库断开导致释放槽位失败，也必须删除本次临时文件。
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException error) { LOG.warn("清理后台校验临时文件失败：job={}", job.id()); }
                }
            }
        }
    }

    private void fence(Job job) {
        var pack = catalog.lock(job.packId()).orElseThrow(StaleJob::new);
        if (!"active".equals(pack.status()) || pack.latestRevision() != job.revision()
                || jobs.fence(job.id(), owner, job.generation(), now()).isEmpty()) throw new StaleJob();
    }

    private void current(Job job) {
        var found = jobs.find(job.id()).orElseThrow(StaleJob::new);
        if (!"running".equals(found.status()) || !owner.equals(found.leaseOwner())
                || found.generation() != job.generation() || !found.leaseUntil().isAfter(now())) throw new StaleJob();
        if (runner.foregroundRecentlyActive()) throw new cloud.treenewbee.quizforge.rules.RuleRunnerClient.BackgroundBusy();
    }

    private void fail(Job job, String code, String reason, boolean retryable) {
        try {
            tx.executeWithoutResult(status -> {
                fence(job);
                if (retryable) {
                    boolean retry = job.attempts() <= 3;
                    metadata.validation(job.packId(), job.revision(), retry ? "queued" : "processing_failed", null);
                    complete(job, retry ? "queued" : "failed", code, now().plusSeconds(5L * job.attempts() * job.attempts()));
                } else {
                    String message = reason == null ? "题库内容未通过校验" : reason.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
                    if (message.codePointCount(0, message.length()) > 500) message = message.substring(0, message.offsetByCodePoints(0, 500));
                    metadata.validation(job.packId(), job.revision(), "rejected", message);
                    catalog.decide(job.packId(), job.revision(), "rejected", null, message, now());
                    event(job.packId(), job.revision(), "validation-rejected", Map.of("code", code, "reason", message));
                    String user = catalog.pack(job.packId()).orElseThrow().ownerId();
                    notifications.publishSystem(user, "题库审核未通过", message);
                    complete(job, "completed", code, now());
                }
            });
        } catch (StaleJob ignored) {
            // 失败结果同样必须通过栅栏。
        }
    }

    private void complete(Job job, String status, String code, Instant next) {
        if (jobs.finish(job.id(), owner, job.generation(), status, code, next, now()) != 1) throw new StaleJob();
    }

    public void reconcileReviews() {
        var candidates = metadata.waitingReview(reconcileAfter, reconcileRevision);
        if (candidates.isEmpty()) {
            reconcileAfter = null;
            reconcileRevision = 0;
        }
        for (var candidate : candidates) {
            tx.executeWithoutResult(status -> {
                var pack = catalog.lock(candidate.packId()).orElseThrow();
                if (!"active".equals(pack.status())) return;
                var revision = catalog.revision(pack.id(), candidate.revision()).orElseThrow();
                if (!"pending".equals(revision.reviewStatus())) return;
                var digests = json.readTree(revision.extensionDigests());
                List<String> values = new java.util.ArrayList<>();
                digests.forEach(digest -> values.add(digest.asString()));
                decideValidated(pack.ownerId(), pack.id(), revision.revision(), values, false);
            });
            reconcileAfter = candidate.packId();
            reconcileRevision = candidate.revision();
        }
    }

    public void retry(String id) {
        tx.executeWithoutResult(status -> {
            Job before = jobs.find(id).orElseThrow(ImportUploadService::notFound);
            catalog.lock(before.packId()).orElseThrow(ImportUploadService::notFound);
            if (jobs.retry(id, now()) != 1) throw new cloud.treenewbee.quizforge.shared.web.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT, "IMPORT_JOB_CHANGED", "任务已变化或不允许重试");
            Job job = jobs.find(id).orElseThrow();
            metadata.validation(job.packId(), job.revision(), "queued", null);
        });
        if (poller != null) poller.wake();
    }

    private void decideValidated(String user, String pack, int revision, List<String> digests, boolean admin) {
        var states = reviews.states(digests);
        boolean approved = digests.stream().allMatch(d -> states.containsKey(d) && "approved".equals(states.get(d).status()));
        var info = metadata.owned(pack, user, revision).orElseThrow(StaleJob::new);
        if (!approved) {
            String reason = digests.stream().map(states::get).filter(java.util.Objects::nonNull)
                    .filter(state -> List.of("rejected", "revoked").contains(state.status()))
                    .map(state -> "拓展「" + state.extensionId() + "」未通过审核或已停用" + (state.reason() == null ? "" : "：" + state.reason()))
                    .findFirst().orElse(null);
            if (reason != null && metadata.notified(pack, revision, "rejected") == 1) {
                event(pack, revision, "extension-rejected", Map.of("reason", reason));
                notifications.publishSystem(user, "题库审核未通过", reason);
            }
            return;
        }
        if (!(admin || "private".equals(info.visibility()))) return;
        if (catalog.decide(pack, revision, "approved", admin ? user : null, null, now()) == 1) {
            catalog.approve(pack, revision, now());
            metadata.notified(pack, revision, "approved");
            event(pack, revision, "validation-approved", Map.of("adminUpload", admin));
            notifications.publishSystem(user, "题库审核通过", "你的题库包已通过校验与审核，可以导入练习空间。");
            if (admin) audit.record(user, "admin", "pack-auto-approved", pack,
                    Map.of("说明", "管理员上传自动批准", "revision", revision));
        }
    }

    private void event(String pack, int revision, String action, Map<String, ?> detail) {
        catalog.insertEvent(Ids.uuidV7(), pack, revision, null, action, json.writeValueAsString(detail), now());
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static final class StaleJob extends RuntimeException {}
}
