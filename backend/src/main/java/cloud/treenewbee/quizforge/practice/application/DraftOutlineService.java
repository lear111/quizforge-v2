package cloud.treenewbee.quizforge.practice.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import cloud.treenewbee.quizforge.practice.infrastructure.DraftOutlineMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 在原草稿与回执提交后、事务外计算本题进度；计算失败不撤销答案。 */
@Service
public class DraftOutlineService {
    private static final Logger LOG = LoggerFactory.getLogger(DraftOutlineService.class);
    public record Progress(JsonNode answerState, JsonNode outlineStates) {}
    private final PracticeReadService reads;
    private final RuleRunnerClient runner;
    private final DraftOutlineMapper mapper;
    private final ReceiptMapper receipts;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Duration deadline;

    public DraftOutlineService(PracticeReadService reads, RuleRunnerClient runner, DraftOutlineMapper mapper,
            ReceiptMapper receipts, TransactionTemplate tx, JsonMapper json,
            @Value("${qf.practice.draft-validation-deadline:250ms}") Duration deadline) {
        this.reads = reads;
        this.runner = runner;
        this.mapper = mapper;
        this.receipts = receipts;
        this.tx = tx;
        this.json = json;
        this.deadline = deadline;
    }

    public Progress complete(String userId, String collectionId, String qid, String contextId,
            String requestId, long revision, JsonNode answer, JsonNode reported, String savedReceipt) {
        JsonNode previous = json.readTree(savedReceipt);
        if (previous.has("draftProgress")) return progress(previous.path("draftProgress"));
        Progress computed = calculate(userId, collectionId, qid, revision, answer, reported);
        return tx.execute(status -> {
            if (mapper.claim(userId, requestId, json.writeValueAsString(computed)) == 1) {
                // 0 行意味着已有新草稿或提交，派生结果丢弃，原请求仍返回自己的回执。
                mapper.update(contextId, userId, qid, revision, encode(computed.outlineStates()),
                        encode(computed.answerState()));
                return computed;
            }
            return progress(receipt(userId, requestId).path("draftProgress"));
        });
    }

    Progress calculate(String userId, String collectionId, String qid, long revision,
            JsonNode answer, JsonNode reported) {
        try {
            var question = reads.ruleQuestion(userId, collectionId, qid);
            Set<String> ids = new LinkedHashSet<>();
            if (question.outline() != null) for (JsonNode item : question.outline().path("items"))
                ids.add(item.path("id").asString());
            var result = runner.execute("validateAnswer", question.rulePackage(),
                    Map.of("data", question.data(), "answer", answer == null ? json.nullNode() : answer,
                            "outlineAfterValidation", true, "outlineRevision", revision), deadline);
            if (!result.ok() || result.data() == null || !result.data().path("valid").asBoolean(false))
                return unknown();
            // API 1.3 的 answer-state 由页面上报；旧拓展由 outlineScore 提供进度。
            JsonNode provided = question.rulePackage().answerStateDeclared() && reported != null
                    ? reported.path("outlineStates") : result.data().path("outlineScore").path("outlineStates");
            Map<String, String> states = new LinkedHashMap<>();
            if (provided.isArray()) for (JsonNode item : provided) {
                String id = item.path("id").asString(), value = item.path("status").asString();
                if (!ids.contains(id) || !Set.of("unanswered", "draft").contains(value)
                        || states.putIfAbsent(id, value) != null) return unknown();
            }
            boolean filled = nonempty(answer);
            var children = json.createArrayNode();
            for (String id : ids) children.addObject().put("id", id)
                    .put("status", provided.isArray() ? states.getOrDefault(id, "unanswered") : filled ? "draft" : "unanswered");
            boolean any = ids.isEmpty() ? filled : children.valueStream().anyMatch(item -> "draft".equals(item.path("status").asString()));
            var state = json.createObjectNode().put("status", any ? "draft" : "unanswered");
            state.set("outlineStates", children);
            return new Progress(state, ids.isEmpty() ? null : children);
        } catch (RuntimeException failed) {
            // 不记录题目、答案、异常消息或路径；详细失败由 runner 指标观察。
            LOG.debug("草稿进度校验失败，已保存答案；进度暂未知（{}）", failed.getClass().getSimpleName());
            return unknown();
        }
    }

    private static boolean nonempty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isString()) return !value.asString().isBlank();
        if (value.isArray() || value.isObject()) return value.valueStream().anyMatch(DraftOutlineService::nonempty);
        return true;
    }
    private Progress unknown() { return new Progress(null, null); }
    private String encode(JsonNode node) { return node == null || node.isNull() ? null : json.writeValueAsString(node); }
    private JsonNode receipt(String userId, String requestId) {
        var row = receipts.find(userId, requestId);
        if (row == null) throw new IllegalStateException("草稿回执缺失");
        return json.readTree(row.response());
    }
    private Progress progress(JsonNode node) {
        return new Progress(node.path("answerState").isNull() ? null : node.get("answerState"),
                node.path("outlineStates").isNull() ? null : node.get("outlineStates"));
    }
}
