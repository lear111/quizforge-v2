package cloud.treenewbee.quizforge.exam;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.practice.application.PracticeLifecycleJobs;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 考试阶段转换先锁 context；规则执行统一交给已有的持久化后台任务。 */
@Service
public class ExamService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher gradingEvents;

    private final ExamMapper exams;
    private final PracticeContextMapper contexts;
    private final PracticeReadMapper reads;
    private final PracticeReadService practice;
    private final LifecycleJobMapper jobs;
    private final PracticeLifecycleJobs worker;
    private final ReceiptMapper receipts;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;

    public ExamService(ExamMapper exams, PracticeContextMapper contexts, PracticeReadMapper reads,
        PracticeReadService practice, LifecycleJobMapper jobs, PracticeLifecycleJobs worker,
        ReceiptMapper receipts, TransactionTemplate tx, Clock clock, JsonMapper json) {
        this.exams = exams;
        this.contexts = contexts;
        this.reads = reads;
        this.practice = practice;
        this.jobs = jobs;
        this.worker = worker;
        this.receipts = receipts;
        this.tx = tx;
        this.clock = clock;
        this.json = json;
    }

    public JsonNode start(String user, String collection, String request, Integer minutes) {
        if (minutes != null && (minutes < 1 || minutes > 1440)) throw invalid("考试时长应为 1～1440 分钟，或不限时");
        var old = exams.creation(user, request).orElse(null);
        if (old != null) return creation(old, collection, minutes);
        if (exams.find(user, collection).isPresent()) throw ContentPages.notFound();
        var bank = reads.collection(user, collection).orElseThrow(ContentPages::notFound);
        // 此方法同时检查审核，但不创建普通练习存档。
        practice.validateBank(user, collection);
        if (!json.readTree(bank.features()).path("exam").asBoolean(true))
            throw new ApiException(HttpStatus.FORBIDDEN, "FEATURE_DISABLED", "该题库未开放考试");
        try {
            return tx.execute(status -> {
                exams.lockCollection(user, collection).orElseThrow(ContentPages::notFound);
                var replay = exams.creation(user, request).orElse(null);
                if (replay != null) return creation(replay, collection, minutes);
                if (exams.inProgress(user, collection) != 0)
                    throw conflict("EXAM_IN_PROGRESS", "该题库已有作答中的考试，请先交卷或删除后再开始");
                Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                String id = Ids.uuidV7(), context = Ids.uuidV7();
                changed(exams.insertContext(context, user, collection, id, now));
                var row = new ExamRow(id, user, collection, context, bank.bankId(), "in_progress", minutes, now,
                    minutes == null ? null:now.plusSeconds(minutes * 60L), null, null, request, 0, bank.title());
                changed(exams.insert(row));
                return metadata(row);
            });
        } catch (DuplicateKeyException concurrent) {
            return creation(exams.creation(user, request).orElseThrow(() -> concurrent), collection, minutes);
        }
    }

    private JsonNode creation(ExamRow row, String collection, Integer minutes) {
        if (row.status().equals("deleted")) throw conflict("EXAM_DELETED", "考试已删除，不能重放创建请求");
        if (!row.collectionId().equals(collection) || !Objects.equals(row.durationMinutes(), minutes))
            throw invalidReuse();
        return metadata(row);
    }

    public JsonNode list(String user, String collection) {
        reads.collection(user, collection).orElseThrow(ContentPages::notFound);
        var result = json.createObjectNode();
        var latest = exams.latest(user, collection).orElse(null);
        if (latest == null) result.put("defaultDurationMinutes", 60);
        else if (latest.durationMinutes() == null) result.putNull("defaultDurationMinutes");
        else result.put("defaultDurationMinutes", latest.durationMinutes());
        var records = result.putArray("records");
        for (var row:exams.list(user, collection)) records.add(metadata(row));
        return result;
    }

    public ExamRow owned(String user, String id) {
        var row = exams.find(user, id).orElseThrow(ContentPages::notFound);
        if (row.status().equals("deleted")) throw ContentPages.notFound();
        return row;
    }

    public ExamRow observe(String user, String id) {
        var row = owned(user, id);
        if (expired(row)) {
            submit(user, id, null);
            row = owned(user, id);
        }
        return row;
    }

    public JsonNode status(String user, String id) {
        return metadata(observe(user, id));
    }

    public JsonNode submit(String user, String id, String request) {
        var known = owned(user, id);
        byte[] hash = hash("exam-submit\n" + id);
        try {
            return tx.execute(status -> {
                contexts.lockForUpdate(known.contextId(), user).orElseThrow(ContentPages::notFound);
                var row = exams.lock(user, id).orElseThrow(ContentPages::notFound);
                if (row.status().equals("deleted")) throw ContentPages.notFound();
                if (request != null) {
                    var replay = receipt(user, request, hash);
                    if (replay != null) return replay;
                }
                var context = contexts.lockForUpdate(row.contextId(), user).orElseThrow();
                var job = context.lifecycleJobId() == null ? null:jobs.find(context.lifecycleJobId(), user, row.collectionId()).orElse(null);
                boolean retry = row.status().equals("grading") && (job == null || job.status().equals("failed"));
                if (row.status().equals("in_progress") || retry) worker.beginExam(row, request != null);
                var response = metadata(exams.find(user, id).orElseThrow());
                if (request != null) receipts.insert(user, request, hash, json.writeValueAsString(response));
                return response;
            });
        } catch (DuplicateKeyException error) {
            return submitReplay(user, request, hash, error);
        } catch (ApiException error) {
            if (error.status() != HttpStatus.CONFLICT) throw error;
            return submitReplay(user, request, hash, error);
        }
    }

    private JsonNode submitReplay(String user, String request, byte[] hash, RuntimeException original) {
        if (request == null) throw original;
        return tx.execute(status -> {
            var replay = receipt(user, request, hash);
            if (replay == null)throw original;
            return replay;
        });
    }
    /** 只包住本题的短 SQL 事务；截止检查在获得锁后重新执行。 */

    public <T> T write(String user, String id, Supplier<T> operation) {
        var known = owned(user, id);
        if (expired(known)) {
            submit(user, id, null);
            throw conflict("EXAM_DEADLINE_PASSED", "考试时间已到，作答已锁定");
        }
        return tx.execute(status -> {
            contexts.lockForUpdate(known.contextId(), user).orElseThrow(ContentPages::notFound);
            var current = exams.lock(user, id).orElseThrow(ContentPages::notFound);
            if (current.status().equals("deleted")) throw ContentPages.notFound();
            if (current.deadlineAt() != null && !clock.instant().isBefore(current.deadlineAt()))
                throw conflict("EXAM_DEADLINE_PASSED", "考试时间已到，作答已锁定");
            if (!current.status().equals("in_progress")) throw conflict("EXAM_LOCKED", "考试已交卷，不能修改作答或白板");
            return operation.get();
        });
    }

    public JsonNode delete(String user, String id) {
        var known = exams.find(user, id).orElseThrow(ContentPages::notFound);
        return tx.execute(status -> {
            contexts.lockForUpdate(known.contextId(), user).orElseThrow(ContentPages::notFound);
            var row = exams.lock(user, id).orElseThrow(ContentPages::notFound);
            if (row.status().equals("completed")) throw conflict("EXAM_COMPLETED", "考试已完成，请从历史记录中删除");
            if (!row.status().equals("deleted")) {
                changed(exams.delete(id, user, row.version()));
                if (gradingEvents != null) gradingEvents.publishEvent(new cloud.treenewbee.quizforge.practice.application.GradingEvents.Invalidated(user, row.contextId(), null));
                changed(contexts.phase(row.contextId(), user, "completed", null, clock.instant()));
                changed(contexts.setCurrentRound(row.contextId(), user, null, clock.instant()));
                exams.purgeStates(row.contextId(), user);
                exams.purgeWhiteboards(row.contextId(), user);
                exams.purgePayloads(row.contextId(), user);
                exams.purgeJobs(row.contextId(), user);
                exams.purgeRounds(row.contextId(), user);
            }
            return json.createObjectNode().put("deleted", id);
        });
    }

    public ObjectNode metadata(ExamRow row) {
        var value = json.createObjectNode().put("id", row.id()).put("status", row.status().replace('_', '-'))
            .put("bankKind", "bank").put("bankId", row.collectionId()).put("title", row.title())
            .put("startedAt", row.startedAt().toString()).put("serverTime", clock.instant().toString())
            .put("deadline", row.deadlineAt() == null ? null:row.deadlineAt().toString()).put("durationMinutes", row.durationMinutes())
            .put("historyId", row.status().equals("completed") ? row.roundId():null).put("version", row.version());
        var errors = value.putArray("gradingErrors");
        var context = contextsState(row);
        if (context != null && context.lifecycleJobId() != null) {
            value.put("jobId", context.lifecycleJobId());
            var job = jobs.find(context.lifecycleJobId(), row.userId(), row.collectionId()).orElse(null);
            if (job != null) {
                value.put("total", job.total()).put("completed", job.completed());
                if (job.status().equals("failed")) errors.addObject().put("code", job.errorCode()).put("message", job.errorMessage());
            }
        }
        return value;
    }

    private PracticeContextRow contextsState(ExamRow row) {
        // 不锁行，不枚举试卷；列表和轮询只读取当前考试及任务元信息。
        return exams.phase(row.contextId(), row.userId()).orElse(null);
    }

    private boolean expired(ExamRow row) {
        return row.status().equals("in_progress") && row.deadlineAt() != null && !clock.instant().isBefore(row.deadlineAt());
    }

    private JsonNode receipt(String user, String request, byte[] hash) {
        var row = receipts.find(user, request);
        if (row == null) return null;
        if (!Arrays.equals(hash, row.requestSha256())) throw invalidReuse();
        return json.readTree(row.response());
    }

    private static byte[] hash(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void changed(int count) {
        if (count != 1)throw new IllegalStateException("考试状态未写入");
    }

    private static ApiException invalidReuse() {
        return new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REUSED", "请求 ID 已被重复使用");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
