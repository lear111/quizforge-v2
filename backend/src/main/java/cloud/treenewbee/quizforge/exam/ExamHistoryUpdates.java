package cloud.treenewbee.quizforge.exam;

import java.time.Clock;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.practice.application.*;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 人工改分与已冻结的考试历史在同一事务内更新。 */
@Component
public class ExamHistoryUpdates {
    private final ExamMapper exams;
    private final PracticeReadService reads;
    private final PracticeSummaryService summaries;
    private final PracticeRoundMapper rounds;
    private final PracticeLifecycleMapper lifecycle;
    private final Clock clock;
    private final JsonMapper json;

    public ExamHistoryUpdates(ExamMapper exams, PracticeReadService reads, PracticeSummaryService summaries,
        PracticeRoundMapper rounds, PracticeLifecycleMapper lifecycle, Clock clock, JsonMapper json) {
        this.exams = exams;
        this.reads = reads;
        this.summaries = summaries;
        this.rounds = rounds;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.json = json;
    }

    public void reviewed(String context, String user, String qid) {
        var exam = exams.context(user, context).orElse(null);
        if (exam == null || exam.roundId() == null || exam.status().equals("deleted")) return;
        // submitted 投影来自已写入状态，此处不执行规则。
        var payload = (ObjectNode)json.valueToTree(reads.question(user, exam.id(), qid, null));
        payload.put("snapshotVersion", 1);
        payload.set("capabilities", json.valueToTree(new PracticeViews.Capabilities(false, false, false, false, false, false)));
        var target = reads.resolveTarget(user, exam.id(), qid);
        if (rounds.upsertRoundQuestion(new PracticeRoundQuestionRow(exam.roundId(), qid, user,
            json.writeValueAsString(payload), target.extensionDigest(), clock.instant()))<1)
        throw new IllegalStateException("考试评分快照未写入");
        var summary = summaries.summary(user, exam.id());
        summary.put("historyId", exam.roundId()).put("canFinish", false).put("canRestart", false);
        if (lifecycle.updateCompletedSummary(exam.roundId(), user, json.writeValueAsString(summary), clock.instant()) != 1)
            throw new IllegalStateException("考试汇总未写入");
    }
}
