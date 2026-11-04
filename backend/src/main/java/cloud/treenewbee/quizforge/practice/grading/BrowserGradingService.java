package cloud.treenewbee.quizforge.practice.grading;

import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionMapper;
import cloud.treenewbee.quizforge.practice.application.*;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 作答先持久化并锁定；只有持有当前双代次的结果可以落库。 */
@Service
public class BrowserGradingService {
    public record Ready() {}
    private final GradingMode mode;
    private final BrowserGradingMapper mapper;
    private final BrowserGradeFormat format;
    private final PracticeReadService reads;
    private final DraftService drafts;
    private final PracticeContextMapper contexts;
    private final PracticeRoundMapper rounds;
    private final LifecycleJobMapper jobs;
    private final ReceiptMapper receipts;
    private final QuestionMapper questions;
    private final ContentPages pages;
    private final SubmitGrader grader;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public BrowserGradingService(GradingMode mode, BrowserGradingMapper mapper, BrowserGradeFormat format,
            PracticeReadService reads, DraftService drafts, PracticeContextMapper contexts, PracticeRoundMapper rounds,
            LifecycleJobMapper jobs, ReceiptMapper receipts, QuestionMapper questions, ContentPages pages,
            SubmitGrader grader, TransactionTemplate tx, Clock clock, JsonMapper json, ApplicationEventPublisher events) {
        this.mode = mode; this.mapper = mapper; this.format = format; this.reads = reads; this.drafts = drafts;
        this.contexts = contexts; this.rounds = rounds; this.jobs = jobs; this.receipts = receipts;
        this.questions = questions; this.pages = pages; this.grader = grader; this.tx = tx;
        this.clock = clock; this.json = json; this.events = events;
    }

    public boolean enabled() { return mode.browser(); }

    public JsonNode submit(String user, String collection, String qid, String request, long revision,
            JsonNode answer, String contentVersion) {
        reads.validateContentVersion(user, collection, qid, contentVersion);
        if (json.writeValueAsString(answer).getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ANSWER_TOO_LARGE", "作答超过 2 MiB 上限");
        byte[] hash = hash("browser-submit\n" + collection + "\n" + qid + "\n" + revision + "\n" + json.writeValueAsString(answer));
        var replay = receipt(user, request, hash);
        if (replay != null) return replay;
        var target = reads.resolveTarget(user, collection, qid);
        try {
            return tx.execute(status -> {
                var context = contexts.lockForUpdate(target.contextId(), user).orElseThrow(ContentPages::notFound);
                var replayAfterLock = receipt(user, request, hash);
                if (replayAfterLock != null) return replayAfterLock;
                PracticeWrites.requireActive(context);
                // 同一个短事务先保存再锁定；不执行规则，回执竞争者在 context 锁后重查。
                long saved = drafts.saveDraft(user, target.contextId(), qid, revision,
                        json.writeValueAsString(answer), target.contentSha256());
                var bank = reads.resolveCollection(user, collection);
                var question = questions.findById(bank.bankId(), qid).orElseThrow(ContentPages::notFound);
                Instant now = clock.instant();
                changed(mapper.lock(context.id(), user, qid, saved, question.maxScore(), now));
                String round = context.currentRoundId();
                if (round == null) {
                    round = Ids.uuidV7();
                    changed(rounds.insertRound(new PracticeRoundRow(round, user, collection, context.id(), "practice",
                            "in_progress", "{}", null, now, now, null, null)));
                    changed(contexts.setCurrentRound(context.id(), user, round, now));
                }
                changed(contexts.bumpProgressVersion(context.id(), user, now));
                var locked = mapper.locked(context.id(), user, qid).orElseThrow();
                String job = Ids.uuidV7();
                changed(jobs.insert(job, user, collection, context.id(), round, "browser_grade", 1, now));
                changed(jobs.deferBrowser(job, user, now.plus(mode.timeout()), json.writeValueAsString(
                        Map.of("qid", qid, "generation", token(locked)))));
                var response = (ObjectNode) json.valueToTree(reads.question(user, collection, qid, null));
                response.put("revision", locked.revision());
                response.set("browserGrading", ticket(user, collection, locked));
                receipts.insert(user, request, hash, json.writeValueAsString(response));
                wakeAfterCommit();
                return receipt(user, request, hash);
            });
        } catch (DuplicateKeyException error) {
            return recheck(user, request, hash, error);
        } catch (ApiException error) {
            if (error.status() != HttpStatus.CONFLICT) throw error;
            return recheck(user, request, hash, error);
        }
    }

    /** 只返回本用户、本上下文中已锁定且仍等待判分的题；单批最多 20 道。 */
    public JsonNode pending(String user, String collection, String qid) {
        var target = reads.resolveCollection(user, collection);
        var values = json.createArrayNode();
        List<BrowserGradingMapper.Locked> pending;
        if (qid == null) pending = mapper.pending(target.contextId(), user);
        else {
            reads.resolveTarget(user, collection, qid);
            var row = mapper.locked(target.contextId(), user, qid).orElseThrow(BrowserGradingService::stale);
            if (!row.gradingStatus().equals("awaiting_browser")) throw stale();
            pending = List.of(row);
        }
        for (var row : pending) values.add(ticket(user, collection, row));
        return json.createObjectNode().set("items", values);
    }

    private JsonNode ticket(String user, String collection, BrowserGradingMapper.Locked row) {
        var question = reads.ruleQuestion(user, collection, row.questionId());
        var result = json.createObjectNode().put("questionId", row.questionId()).put("generation", token(row));
        result.set("data", question.data());
        result.set("answer", json.readTree(row.answer()));
        result.set("package", json.valueToTree(question.rulePackage()));
        try {
            result.put("source", pages.browserRules(pages.extension(java.util.HexFormat.of().parseHex(question.rulePackage().digest()))));
        } catch (java.io.IOException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RULE_UNAVAILABLE", "规则文件暂时不可用");
        }
        return result;
    }

    public JsonNode report(String user, String collection, String qid, String generation, JsonNode report) {
        var target = reads.resolveTarget(user, collection, qid);
        int[] token = parseToken(generation);
        var metadata = reads.prepareDraftSave(user, collection, qid);
        var known = mapper.locked(target.contextId(), user, qid).orElseThrow(BrowserGradingService::stale);
        var outcome = format.parse(report, metadata.outline(), json.readTree(known.answer()));
        byte[] hash = hash(json.writeValueAsString(report));
        return tx.execute(status -> {
            var context = contexts.lockForUpdate(target.contextId(), user).orElseThrow(ContentPages::notFound);
            var current = mapper.locked(context.id(), user, qid).orElseThrow(BrowserGradingService::stale);
            if (current.submissionGeneration() != token[0] || current.gradingGeneration() != token[1]) throw stale();
            if (!current.gradingStatus().equals("awaiting_browser")) {
                if (!"browser".equals(current.gradingSource()) || !Arrays.equals(current.browserReportSha256(), hash)) throw stale();
            } else {
                changed(apply(context.id(), user, qid, current, outcome, "browser", hash));
                changed(contexts.bumpProgressVersion(context.id(), user, clock.instant()));
                if (context.lifecycleJobId() != null && mapper.remaining(context.id(), user) == 0)
                    jobs.browserReady(context.lifecycleJobId(), user, clock.instant());
                wakeAfterCommit();
            }
            var response = (ObjectNode) json.valueToTree(reads.question(user, collection, qid, null));
            response.put("revision", response.path("state").path("revision").asLong());
            return response;
        });
    }

    public int lockBatch(String user, String context, String bank) {
        mapper.lockBatch(context, user, bank, clock.instant());
        return mapper.remaining(context, user);
    }

    public void deferBatch(String job, String user) {
        changed(jobs.deferBrowser(job, user, clock.instant().plus(mode.timeout()), null));
    }

    /** 原有任务线程调用；租约栅栏与代次条件必须在写结果的同一事务中检查。 */
    public boolean fallback(LifecycleJobMapper.Job job, String scope, Runnable leaseFence) {
        BrowserGradingMapper.Locked work;
        if (job.kind().equals("browser_grade")) {
            var payload = json.readTree(job.result());
            work = mapper.locked(job.contextId(), job.userId(), payload.path("qid").asString()).orElse(null);
            if (work != null && !token(work).equals(payload.path("generation").asString())) work = null;
        } else work = mapper.pending(job.contextId(), job.userId()).stream().findFirst().orElse(null);
        if (work == null || !work.gradingStatus().equals("awaiting_browser")) return false;
        var selected = work;
        var outcome = grader.grade(job.userId(), scope, work.questionId(), json.readTree(work.answer()));
        tx.executeWithoutResult(status -> {
            contexts.lockForUpdate(job.contextId(), job.userId()).orElseThrow(ContentPages::notFound);
            leaseFence.run();
            if (apply(job.contextId(), job.userId(), selected.questionId(), selected, outcome, "server", null) == 1)
                changed(contexts.bumpProgressVersion(job.contextId(), job.userId(), clock.instant()));
        });
        return true;
    }

    public int remaining(String user, String context) { return mapper.remaining(context, user); }
    public int unfinished(String user, String context) { return mapper.unfinished(context, user); }
    public void serverSource(String user, String context, String qid, long revision) {
        changed(mapper.serverSource(context, user, qid, revision));
    }

    private int apply(String context, String user, String qid, BrowserGradingMapper.Locked row,
            GradeOutcome outcome, String source, byte[] hash) {
        return mapper.apply(context, user, qid, row.submissionGeneration(), row.gradingGeneration(),
                outcome.resultJson(), outcome.projectedJson(), scale(outcome.score()), scale(outcome.maxScore()),
                outcome.gradingStatus(), outcome.outlineStatesJson(), source, hash, clock.instant());
    }

    private static java.math.BigDecimal scale(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static String token(BrowserGradingMapper.Locked row) { return row.submissionGeneration() + ":" + row.gradingGeneration(); }
    private static int[] parseToken(String value) {
        try {
            if (value == null || !value.matches("[0-9]{1,10}:[0-9]{1,10}")) throw stale();
            var parts = value.split(":");
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (NumberFormatException error) { throw stale(); }
    }

    private JsonNode receipt(String user, String request, byte[] hash) {
        var row = receipts.find(user, request);
        if (row == null) return null;
        if (!Arrays.equals(hash, row.requestSha256()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REUSED", "请求 ID 已被重复使用");
        return json.readTree(row.response());
    }

    private JsonNode recheck(String user, String request, byte[] hash, RuntimeException error) {
        return tx.execute(status -> {
            var replay = receipt(user, request, hash);
            if (replay == null) throw error;
            return replay;
        });
    }

    private void wakeAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { events.publishEvent(new Ready()); }
        });
    }

    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void changed(int count) { if (count != 1) throw stale(); }
    private static ApiException stale() {
        return new ApiException(HttpStatus.CONFLICT, "GRADING_GENERATION_CONFLICT", "提交代次已变化或判分结果已确定，请刷新后重试");
    }
}
