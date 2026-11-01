package cloud.treenewbee.quizforge.practice.application;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 带租约的持久化任务；每次执行一道题，调用规则期间不持有数据库事务。 */
@Service
public class PracticeLifecycleJobs {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PracticeLifecycleJobs.class);
    private final LifecycleJobMapper jobs;
    private final PracticeContextMapper contexts;
    private final PracticeLifecycleMapper lifecycle;
    private final PracticeRoundMapper rounds;
    private final QuestionStateMapper states;
    private final PracticeReadService reads;
    private final PracticeSummaryService summaries;
    private final PracticeHistoryMapper histories;
    private final ReceiptMapper receipts;
    private final SubmitGrader grader;
    private final RuleRunnerClient runner;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final String owner=UUID.randomUUID().toString();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"practice-lifecycle");t.setDaemon(true);return t;});
    @Value("${qf.practice.jobs.enabled:true}") boolean enabled;
    @Value("${qf.practice.jobs.interval-ms:250}") long interval;

    public PracticeLifecycleJobs(LifecycleJobMapper jobs,PracticeContextMapper contexts,PracticeLifecycleMapper lifecycle,
            PracticeRoundMapper rounds,QuestionStateMapper states,PracticeReadService reads,PracticeSummaryService summaries,
            PracticeHistoryMapper histories,ReceiptMapper receipts,SubmitGrader grader,RuleRunnerClient runner,
            TransactionTemplate tx,JsonMapper json,Clock clock) {
        this.jobs=jobs;this.contexts=contexts;this.lifecycle=lifecycle;this.rounds=rounds;this.states=states;this.reads=reads;
        this.summaries=summaries;this.histories=histories;this.receipts=receipts;this.grader=grader;this.runner=runner;
        this.tx=tx;this.json=json;this.clock=clock;
    }
    @PostConstruct void start() { if(enabled)worker.scheduleWithFixedDelay(this::safeTick,100,Math.max(10,interval),TimeUnit.MILLISECONDS); }
    @PreDestroy void stop() { worker.shutdownNow(); }
    private void safeTick() { try { tick(); } catch(Exception error) { LOG.warn("练习后台任务调度失败",error); } }

    public JsonNode begin(String user,String collection,String request,String roundId,String version,String kind) {
        var target=reads.resolveCollection(user,collection);
        byte[] hash=hash(kind+"\n"+collection+"\n"+roundId+"\n"+version);
        try {
            return tx.execute(status->{
                var context=contexts.lockForUpdate(target.contextId(),user).orElseThrow();
                var replay=receipt(user,request,hash);if(replay!=null)return replay;
                PracticeWrites.requireStable(context);
                if(kind.equals("archive")&&!"completed".equals(context.completionStatus()))
                    throw conflict("PRACTICE_NOT_FINISHED","请先完成练习，再练一次");
                if(kind.equals("finish"))PracticeWrites.requireActive(context);
                lifecycle.lockStates(context.id(),user,null);
                var summary=summaries.current(user,target.bankId(),context);
                if(!java.util.Objects.equals(roundId,context.currentRoundId())||!version.equals(summary.path("summaryVersion").asString()))
                    throw conflict("SUMMARY_CONFLICT","练习数据已更新，请刷新汇总后重试");
                Instant now=clock.instant();String actualRound=context.currentRoundId();
                if(actualRound==null) {
                    actualRound=Ids.uuidV7();
                    changed(rounds.insertRound(new PracticeRoundRow(actualRound,user,collection,context.id(),"practice","in_progress","{}",null,now,now,null,null)));
                    changed(contexts.setCurrentRound(context.id(),user,actualRound,now));
                }
                int total=kind.equals("finish")?summary.path("answeredUnsubmittedCount").asInt():summary.path("questionCount").asInt();
                String id=Ids.uuidV7();
                if(kind.equals("archive")) {
                    // 每次接受的再练请求只计算一次；此时尚未重置当前作答。
                    var snapshot=(ObjectNode)json.readTree(reads.collectionSnapshot(user,collection));snapshot.put("snapshotVersion",1);snapshot.put("sourceBankId",target.bankId());
                    snapshot.set("listeningSource",histories.listening(target.bankId()).map(json::readTree).orElse(json.nullNode()));
                    changed(jobs.collection(actualRound,user,json.writeValueAsString(snapshot),now));
                    histories.copyAssets(actualRound,target.bankId());
                }
                changed(jobs.insert(id,user,collection,context.id(),actualRound,kind,total,now));
                changed(contexts.phase(context.id(),user,kind.equals("finish")?"finishing":"archiving",id,now));
                var response=json.createObjectNode().put("jobId",id).put("status","queued").put("total",total).put("completed",0);
                receipts.insert(user,request,hash,json.writeValueAsString(response));
                return receipt(user,request,hash);
            });
        } catch(org.springframework.dao.DuplicateKeyException error) { return recheck(user,request,hash,error); }
        catch(ApiException error) { if(error.status()!=HttpStatus.CONFLICT)throw error;return recheck(user,request,hash,error); }
    }
    public JsonNode progress(String user,String collection,String id) {
        // 查询所属任务只读一行，不执行规则、不遍历题库。
        var job=jobs.find(id,user,collection).orElseThrow(cloud.treenewbee.quizforge.content.application.ContentPages::notFound);
        var value=json.createObjectNode().put("jobId",id).put("kind",job.kind()).put("status",job.status())
                .put("total",job.total()).put("completed",job.completed());
        if(job.result()!=null)value.set("result",json.readTree(job.result()));
        if(job.errorCode()!=null)value.set("error",json.createObjectNode().put("code",job.errorCode()).put("message",job.errorMessage()));
        return value;
    }
    /** 测试可门控推进；生产环境只由唯一后台工作线程调用。 */
    public synchronized void tick() {
        var job=tx.execute(status->{var next=jobs.next(clock.instant()).orElse(null);if(next==null)return null;
            changed(jobs.claim(next.id(),owner,clock.instant().plusSeconds(120),clock.instant()));
            return jobs.find(next.id(),next.userId(),next.collectionId()).orElseThrow();});
        if(job==null)return;
        try { runner.background(()->{step(job);return null;}); }
        catch(RuleRunnerClient.BackgroundBusy busy) { jobs.yield(job.id(),owner,clock.instant()); }
        catch(Exception error) { fail(job,error); }
    }
    private void step(LifecycleJobMapper.Job job) {
        var target=reads.resolveCollection(job.userId(),job.collectionId());
        if(job.kind().equals("finish")) {
            var work=jobs.nextAnswer(target.bankId(),job.contextId(),job.userId()).orElse(null);
            if(work!=null) {
                var outcome=grader.grade(job.userId(),job.collectionId(),work.questionId(),json.readTree(work.answer()));
                tx.executeWithoutResult(status->{check(job);
                    if(states.submit(outcome.resultJson(),outcome.gradingStatus(),scale(outcome.score()),scale(outcome.maxScore()),
                            outcome.outlineStatesJson(),outcome.projectedJson(),clock.instant(),job.contextId(),work.questionId(),job.userId(),work.revision())!=1)
                        throw conflict("REVISION_CONFLICT","批量判分期间作答已变化");
                    changed(contexts.bumpProgressVersion(job.contextId(),job.userId(),clock.instant()));
                    progress(job,job.completed()+1);
                });
                jobs.yield(job.id(),owner,clock.instant());return;
            }
            tx.executeWithoutResult(status->{var context=check(job);
                changed(contexts.phase(job.contextId(),job.userId(),"completed",job.id(),clock.instant()));
                changed(lifecycle.complete(job.roundId(),job.userId(),"{}",clock.instant()));
                var summary=summaries.current(job.userId(),target.bankId(),contexts.lockForUpdate(context.id(),job.userId()).orElseThrow());
                changed(lifecycle.updateCompletedSummary(job.roundId(),job.userId(),json.writeValueAsString(summary),clock.instant()));
                finish(job,summary);
            });
        } else {
            var qid=jobs.questionAt(target.bankId(),job.completed()).orElse(null);
            if(qid!=null) {
                var payload=(ObjectNode)json.valueToTree(reads.question(job.userId(),job.collectionId(),qid,null));
                // 空答案草稿没有参与完成判分，历史中与从未作答一致。
                var state=(ObjectNode)payload.get("state");
                if(!"submitted".equals(state.path("status").asString()))state.put("status","unanswered");
                payload.put("snapshotVersion",1);payload.set("capabilities",json.valueToTree(new PracticeViews.Capabilities(false,false,false,false,false,false)));
                var signals=jobs.signals(job.contextId(),qid,job.userId()).orElse(new LifecycleJobMapper.Signals(0,null,null));
                payload.set("practiceSignals",json.valueToTree(signals));
                var digest=reads.resolveTarget(job.userId(),job.collectionId(),qid).extensionDigest();
                tx.executeWithoutResult(status->{check(job);
                    if(rounds.upsertRoundQuestion(new PracticeRoundQuestionRow(job.roundId(),qid,job.userId(),json.writeValueAsString(payload),digest,clock.instant()))<1)throw new IllegalStateException("历史单题未写入");
                    progress(job,job.completed()+1);
                });
                jobs.yield(job.id(),owner,clock.instant());return;
            }
            tx.executeWithoutResult(status->{var context=check(job);
                var summary=summaries.current(job.userId(),target.bankId(),context);summary.put("historyId",job.roundId());
                summary.put("processing",false).put("jobId",(String)null).put("canFinish",false).put("canRestart",false);
                if(jobs.freeze(job.roundId(),job.userId(),json.writeValueAsString(summary),clock.instant())!=1)throw new IllegalStateException("历史已冻结");
                lifecycle.reset(context.id(),job.userId(),clock.instant());lifecycle.resetWhiteboards(context.id(),job.userId(),clock.instant());
                changed(contexts.setCurrentRound(context.id(),job.userId(),null,clock.instant()));
                changed(contexts.phase(context.id(),job.userId(),"active",null,clock.instant()));changed(contexts.bumpProgressVersion(context.id(),job.userId(),clock.instant()));
                finish(job,json.createObjectNode().put("restarted",true).put("historyId",job.roundId()));
            });
        }
    }
    private PracticeContextRow check(LifecycleJobMapper.Job job) {
        var context=contexts.lockForUpdate(job.contextId(),job.userId()).orElseThrow();
        if(!job.id().equals(context.lifecycleJobId())||!job.roundId().equals(context.currentRoundId()))throw conflict("PRACTICE_CHANGED","练习轮次已变化");
        // 条件心跳阻止租约已被其他实例接管的旧工作线程提交结果。
        if(jobs.progress(job.id(),owner,job.completed(),clock.instant().plusSeconds(120),clock.instant())!=1)
            throw conflict("JOB_LEASE_LOST","后台任务已由另一工作进程接管");
        return context;
    }
    private void progress(LifecycleJobMapper.Job job,int done) {
        if(jobs.progress(job.id(),owner,done,clock.instant().plusSeconds(120),clock.instant())!=1)throw new IllegalStateException("任务租约丢失");
    }
    private void finish(LifecycleJobMapper.Job job,JsonNode result) {
        if(jobs.end(job.id(),owner,"completed",json.writeValueAsString(result),null,null,clock.instant())!=1)throw new IllegalStateException("任务租约丢失");
    }
    private void fail(LifecycleJobMapper.Job job,Exception error) {
        String code=error instanceof ApiException api?api.code():error instanceof RuleRunnerException?"RULE_UNAVAILABLE":"INTERNAL_ERROR";
        String message=error instanceof ApiException api?api.getMessage():"后台任务失败，作答已保留，请稍后重试";
        LOG.warn("练习 {} 任务 {} 失败：{}",job.kind(),job.id(),code);
        tx.executeWithoutResult(status->{var context=contexts.lockForUpdate(job.contextId(),job.userId()).orElse(null);
            if(context==null)return;
            if(jobs.end(job.id(),owner,"failed",null,code,message,clock.instant())==1&&job.id().equals(context.lifecycleJobId()))
                changed(contexts.phase(context.id(),job.userId(),job.kind().equals("finish")?"active":"completed",job.id(),clock.instant()));
        });
    }
    private JsonNode recheck(String user,String request,byte[] hash,RuntimeException error) {
        return tx.execute(status->{var value=receipt(user,request,hash);if(value==null)throw error;return value;});
    }
    private JsonNode receipt(String user,String request,byte[] hash) {
        var row=receipts.find(user,request);if(row==null)return null;
        if(!Arrays.equals(row.requestSha256(),hash))throw new ApiException(HttpStatus.BAD_REQUEST,"REQUEST_ID_REUSED","请求 ID 已被重复使用");
        return json.readTree(row.response());
    }
    private static byte[] hash(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static java.math.BigDecimal scale(java.math.BigDecimal value) { return value==null?null:value.setScale(4,java.math.RoundingMode.HALF_UP); }
    private static void changed(int count) { if(count!=1)throw new IllegalStateException("练习状态未更新"); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
}
