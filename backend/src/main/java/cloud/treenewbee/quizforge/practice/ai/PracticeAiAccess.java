package cloud.treenewbee.quizforge.practice.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.exam.ExamMapper;
import cloud.treenewbee.quizforge.exam.ExamHistoryUpdates;
import cloud.treenewbee.quizforge.practice.application.*;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** AI 模块通过此处访问练习；原始数据只在已提交检查之后交给服务器规则。 */
@Service
public class PracticeAiAccess {
    public record View(PracticeAiMapper.Target target, String scope, JsonNode answer, JsonNode state) {}
    private final PracticeReadService reads;
    private final PracticeAiMapper mapper;
    private final PracticeContextMapper contexts;
    private final PracticeLifecycleMapper lifecycle;
    private final PracticeSummaryService summaries;
    private final ReviewGrader reviewer;
    private final ExamMapper exams;
    private final ExamHistoryUpdates examHistory;
    private final RuleRunnerClient runner;
    private final JsonMapper json;
    private final Clock clock;

    public PracticeAiAccess(PracticeReadService reads, PracticeAiMapper mapper, PracticeContextMapper contexts,
            PracticeLifecycleMapper lifecycle, PracticeSummaryService summaries, ReviewGrader reviewer, ExamMapper exams,
            ExamHistoryUpdates examHistory, RuleRunnerClient runner, JsonMapper json, Clock clock) {
        this.reads=reads; this.mapper=mapper; this.contexts=contexts; this.lifecycle=lifecycle;
        this.summaries=summaries; this.reviewer=reviewer; this.exams=exams; this.examHistory=examHistory;
        this.runner=runner; this.json=json; this.clock=clock;
    }

    public View view(String user, String kind, String id, String qid) {
        var exam=exams.find(user,id).orElse(null);
        if (!java.util.Set.of("bank","exam").contains(kind) || (exam!=null)!=kind.equals("exam")) throw ContentPages.notFound();
        if (exam!=null && !java.util.Set.of("grading","completed").contains(exam.status()))
            throw new ApiException(HttpStatus.CONFLICT,"EXAM_IN_PROGRESS","考试交卷后才能 AI 评分");
        var source=reads.resolveTarget(user,id,qid);
        var target=mapper.target(source.contextId(),user,qid).orElseThrow(PracticeAiAccess::unavailable);
        if (!"submitted".equals(target.status()) || "awaiting_browser".equals(target.gradingStatus()) || target.roundId()==null)
            throw unavailable();
        if (!Arrays.equals(source.contentSha256(), target.contentSha256()))
            throw new ApiException(HttpStatus.CONFLICT, "AI_TASK_SUPERSEDED", "题目内容已变化，请重新作答");
        if ("archiving".equals(target.completionStatus())) throw unavailable();
        return new View(target,id,json.readTree(target.answer()),state(target));
    }

    /** 自动登记只读本题状态，不取原始数据，不调用规则。 */
    public java.util.Optional<PracticeAiMapper.Target> submitted(String user,String context,String qid) {
        return mapper.target(context,user,qid).filter(t->t.status().equals("submitted")
                && !t.gradingStatus().equals("awaiting_browser") && t.roundId()!=null);
    }

    public static String kind(PracticeAiMapper.Target target) { return target.kind().equals("exam")?"exam":"bank"; }
    public boolean supportsAi(PracticeAiMapper.Target target) {
        return mapper.supportsAi(target.contextId(), target.userId(), target.questionId());
    }
    public static String scope(PracticeAiMapper.Target target) { return target.kind().equals("exam")?target.scopeKey():target.collectionId(); }
    public JsonNode prepare(View view,String target,boolean automatic) {
        var source=reads.ruleQuestion(view.target().userId(),view.scope(),view.target().questionId());
        var input=new java.util.LinkedHashMap<String,Object>();
        input.put("data",source.data()); input.put("answer",view.answer()); input.put("state",view.state());
        if (!target.isEmpty()) input.put("targetId",target);
        if (automatic) input.put("automatic",true);
        var result=runner.background(()->runner.execute("prepareAiGrading",source.rulePackage(),input));
        if (!result.ok()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"INVALID_AI_INPUT","题型无法提供 AI 评分材料");
        return result.data().get("gradingInput");
    }

    public GradeOutcome review(View view,String target,JsonNode candidate) {
        var review=(ObjectNode)candidate.deepCopy();
        if (!target.isEmpty()) review.put("targetId",target);
        return runner.background(()->reviewer.grade(view.target().userId(),view.scope(),view.target().questionId(),
                view.answer(),view.state(),review));
    }

    public boolean matches(View view,String round,int submission,int grading,byte[] answerHash,byte[] contentHash) {
        var t=view.target();
        return Objects.equals(t.roundId(),round) && t.submissionGeneration()==submission && t.gradingGeneration()==grading
                && Arrays.equals(contentHash,t.contentSha256()) && Arrays.equals(answerHash,hash(json.writeValueAsString(view.answer())));
    }

    public void lock(String user,String context) { contexts.lockForUpdate(context,user).orElseThrow(ContentPages::notFound); }

    /** 调用方已锁 context；AI 小题采用不增加整题评分代次，避免使兄弟任务失效。 */
    public boolean apply(View view,GradeOutcome outcome,JsonNode assessment) {
        var t=view.target();
        var decorated=(ObjectNode)json.readTree(outcome.resultJson());
        decorated.put("gradedAt",clock.instant().toString());
        String feedback="最终得分："+decorated.path("score").asString()+" / "+decorated.path("maxScore").asString()+" 分";
        if (assessment.has("targetId")) {
            var previous=view.state().path("result").path("aiAssessments");
            var assessments=previous.isObject()?(ObjectNode)previous.deepCopy():json.createObjectNode();
            assessments.set(assessment.path("targetId").asString(),assessment);
            decorated.set("aiAssessments",assessments); decorated.remove("aiAssessment");
            decorated.put("gradingSource","ai-assisted");
            if (decorated.path("feedback").isString() && !decorated.path("feedback").asString().isBlank()) feedback+="\n\n"+decorated.path("feedback").asString();
            feedback+="\n\n小题 "+assessment.path("targetId").asString()+" AI 建议："+assessment.path("score").asString()+" / "+assessment.path("maxScore").asString()+" 分\n"+assessment.path("feedback").asString();
        } else {
            decorated.set("aiAssessment",assessment); decorated.remove("aiAssessments");
            boolean adjusted=!decorated.path("score").equals(assessment.path("score")) || !decorated.path("maxScore").equals(assessment.path("maxScore"));
            decorated.put("gradingSource",adjusted?"ai-assisted":"ai");
            if (adjusted) feedback+="\n拓展处理；AI 建议："+assessment.path("score").asString()+" / "+assessment.path("maxScore").asString()+" 分";
            feedback+="\n\nAI 评分依据：\n"+assessment.path("feedback").asString();
        }
        if (feedback.length()>20000) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"AI_FEEDBACK_TOO_LARGE","合并后的评分反馈超过上限");
        decorated.put("feedback",feedback);
        int changed=mapper.apply(t.contextId(),t.userId(),t.questionId(),t.submissionGeneration(),t.gradingGeneration(),
                t.revision(),t.roundId(),json.writeValueAsString(decorated),outcome.projectedJson(),scale(outcome.score()),scale(outcome.maxScore()),
                outcome.gradingStatus(),outcome.outlineStatesJson(),clock.instant());
        if (changed==0) return false;
        if (contexts.bumpProgressVersion(t.contextId(),t.userId(),clock.instant())!=1) throw new IllegalStateException("AI 评分汇总未更新");
        var exam=exams.context(t.userId(),t.contextId()).orElse(null);
        if (exam!=null && exam.status().equals("completed")) examHistory.reviewed(t.contextId(),t.userId(),t.questionId());
        else if (exam==null) {
            var round=lifecycle.round(t.roundId(),t.contextId(),t.userId()).orElseThrow();
            if (round.status().equals("completed")) {
                var payload=(ObjectNode)json.valueToTree(reads.question(t.userId(),view.scope(),t.questionId(),null));
                lifecycle.updatePayload(t.roundId(),t.questionId(),t.userId(),json.writeValueAsString(payload),clock.instant());
                if (lifecycle.updateCompletedSummary(t.roundId(),t.userId(),json.writeValueAsString(summaries.summary(t.userId(),view.scope())),clock.instant())!=1)
                    throw new IllegalStateException("AI 评分历史汇总未更新");
            }
        }
        return true;
    }

    public JsonNode payload(View view) { return json.valueToTree(reads.question(view.target().userId(),view.scope(),view.target().questionId(),null)); }
    private JsonNode state(PracticeAiMapper.Target t) {
        var s=json.createObjectNode().put("submitted",true).put("status",t.status()).put("revision",t.revision());
        s.set("answer",json.readTree(t.answer())); s.set("result",t.result()==null?json.nullNode():json.readTree(t.result()));
        return s;
    }
    public static byte[] hash(String text) {
        try {return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static java.math.BigDecimal scale(java.math.BigDecimal n) {return n==null?null:n.setScale(4,java.math.RoundingMode.HALF_UP);}
    private static ApiException unavailable() {return new ApiException(HttpStatus.CONFLICT,"AI_GRADING_UNAVAILABLE","请先提交作答并等待规则判分完成");}
}
