package cloud.treenewbee.quizforge.practice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextRow;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeLifecycleMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** SQL-only summary: pending scores remain null in rows and contribute zero to the aggregate. */
@Service
public class PracticeSummaryService {
    private final PracticeReadService reads;
    private final PracticeLifecycleMapper mapper;
    private final TransactionTemplate tx;
    private final QuestionStamps stamps;
    private final JsonMapper json;

    public PracticeSummaryService(PracticeReadService reads, PracticeLifecycleMapper mapper, TransactionTemplate tx,
            QuestionStamps stamps, JsonMapper json) {
        this.reads = reads; this.mapper = mapper; this.tx = tx; this.stamps = stamps; this.json = json;
    }

    public ObjectNode summary(String userId, String collectionId) {
        var target = reads.resolveCollection(userId, collectionId);
        return tx.execute(status -> current(userId, target.bankId(),
                mapper.context(target.contextId(), userId).orElseThrow()));
    }

    ObjectNode current(String userId, String bankId, PracticeContextRow context) {
        var totals = mapper.totals(bankId, context.id(), userId);
        var rows = mapper.summaryQuestions(bankId, context.id(), userId);
        var round = context.currentRoundId() == null ? null
                : mapper.round(context.currentRoundId(), context.id(), userId).orElseThrow();
        boolean finished = "completed".equals(context.completionStatus()) || "archiving".equals(context.completionStatus());
        var value = json.createObjectNode();
        value.put("questionCount", totals.questionCount()); value.put("submittedCount", totals.submittedCount());
        value.put("pendingCount", totals.pendingCount()); value.put("gradedCount", totals.submittedCount() - totals.pendingCount());
        value.put("score", totals.score()); value.put("maxScore", totals.maxScore());
        value.put("answeredUnsubmittedCount", rows.stream().filter(PracticeLifecycleMapper.SummaryQuestion::answered).count());
        value.put("finished", finished); value.put("status", finished ? "completed" : "in-progress");
        value.put("roundId", context.currentRoundId()); value.put("historyId", (String)null);
        value.put("canFinish", "active".equals(context.completionStatus())); value.put("canRestart", "completed".equals(context.completionStatus()));
        value.put("processing", "finishing".equals(context.completionStatus()) || "archiving".equals(context.completionStatus()));
        value.put("jobId", context.lifecycleJobId());
        var questions = value.putArray("questions");
        for (var row : rows) {
            var item = questions.addObject();
            boolean submitted = "submitted".equals(row.status());
            item.put("id", row.id()); item.put("status", row.status()); item.put("submitted", submitted);
            item.put("gradingStatus", submitted ? row.gradingStatus() : "unsubmitted");
            item.put("score", submitted ? row.score() : null); item.put("maxScore", row.maxScore());
            if (row.outlineStates() != null) item.set("outlineStates", json.readTree(row.outlineStates()));
        }
        // Drafts intentionally do not bump progress_version. Include their revisions so a stale
        // summary cannot silently authorize discarding a draft saved in another tab.
        byte[] digest = digest(json.writeValueAsString(List.of(context.progressVersion(),
                rows.stream().map(row -> List.of(row.id(), row.revision())).toList(), value)));
        value.put("summaryVersion", stamps.stamp(context.id(), "summary", 0, digest, digest).contentVersion());
        return value;
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
