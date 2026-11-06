package cloud.treenewbee.quizforge.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cloud.treenewbee.quizforge.content.application.ImportQueueJobs;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.practice.application.LibraryPracticeService;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 测试夹具也走原文件上传、持久化队列、白名单与快速创建实例，不保留同步导入实现。 */
@Component
public class QueuedPracticeImports {
    public record ImportedCollection(String id, String bankId, String title, String groupName) {}
    private final ImportUploadService uploads;
    private final ImportQueueJobs queue;
    private final ImportQueueMapper jobs;
    private final LibraryPracticeService practice;
    private final JdbcTemplate jdbc;
    private final RuleRunnerClient runner;

    public QueuedPracticeImports(ImportUploadService uploads, ImportQueueJobs queue, ImportQueueMapper jobs,
            LibraryPracticeService practice, JdbcTemplate jdbc, RuleRunnerClient runner) {
        this.uploads = uploads;
        this.queue = queue;
        this.jobs = jobs;
        this.practice = practice;
        this.jdbc = jdbc;
        this.runner = runner;
    }

    public List<ImportedCollection> importArchive(String user, String filename, InputStream input) {
        return importArchive(user, filename, input, null);
    }

    public List<ImportedCollection> importArchive(String user, String filename, InputStream input, String group) {
        try (input) {
            var accepted = uploads.receivePrivate(user, null, filename, -1, input);
            validate(accepted);
            approve(accepted.packId());
            return create(user, accepted.packId(), accepted.revision(), group);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    // 队列并发由专门用例验证；其他用例的夹具只显式推进自己的准备步骤。
    public synchronized void validate(ImportUploadService.Accepted accepted) {
        String id = jdbc.queryForObject("SELECT id FROM content_import_job WHERE pack_id=? AND revision=?",
                String.class, accepted.packId(), accepted.revision());
        await().atMost(Duration.ofSeconds(10)).until(() -> !runner.foregroundRecentlyActive());
        while (jobs.find(id).orElseThrow().status().equals("queued")) {
            assertThat(queue.runOne()).as("推进已接收的测试包").isTrue();
            var job = jobs.find(id).orElseThrow();
            if (job.failureCode() != null) throw new ImportFailure(job.failureCode(), "后台校验失败：" + job.failureCode());
        }
        var job = jobs.find(id).orElseThrow();
        if (!job.status().equals("completed") || job.failureCode() != null)
            throw new ImportFailure(job.failureCode() == null ? "PROCESSING_FAILED" : job.failureCode(), "后台校验未通过");
    }

    public void approve(String pack) {
        jdbc.update("""
                UPDATE admin_extension_review r JOIN catalog_pack_revision v ON v.pack_id=?
                SET r.status='approved' WHERE JSON_CONTAINS(v.extension_digests,JSON_QUOTE(LOWER(HEX(r.digest))))
                """, pack);
    }

    public List<ImportedCollection> create(String user, String pack, int revision) {
        return create(user, pack, revision, null);
    }

    private List<ImportedCollection> create(String user, String pack, int revision, String group) {
        var banks = jdbc.queryForList("SELECT bank_uuid FROM catalog_pack_bank WHERE pack_id=? AND revision=? ORDER BY position",
                String.class, pack, revision);
        return practice.create(user, pack, revision, banks, group, true).stream()
                .map(row -> new ImportedCollection(row.id(), row.bankId(), row.title(), group)).toList();
    }
}
