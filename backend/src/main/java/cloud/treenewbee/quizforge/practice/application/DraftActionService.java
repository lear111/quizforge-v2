package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.practice.application.PracticeViews.*;
import cloud.treenewbee.quizforge.practice.infrastructure.DraftOutlineMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 草稿、显示进度与回执在原有短事务中提交，不执行拓展代码。 */
@Service
public class DraftActionService {
    private final PracticeReadService reads;
    private final IdempotentDraftService drafts;
    private final QuestionStamps stamps;
    private final JsonMapper json;
    private final DraftOutlineService outlines;
    private final DraftOutlineMapper mapper;

    public DraftActionService(PracticeReadService reads, IdempotentDraftService drafts, QuestionStamps stamps,
            JsonMapper json, DraftOutlineService outlines, DraftOutlineMapper mapper) {
        this.reads = reads;
        this.drafts = drafts;
        this.stamps = stamps;
        this.json = json;
        this.outlines = outlines;
        this.mapper = mapper;
    }

    public DraftSaved save(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode answer, String contentVersion) {
        return save(userId, collectionId, qid, requestId, revision, answer, contentVersion, null);
    }

    public DraftSaved save(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode answer, String contentVersion, JsonNode reported) {
        var target = reads.prepareDraftSave(userId, collectionId, qid);
        var version = stamps.stamp(target.contextId(), qid, 0, target.contentSha256(), target.extensionDigest());
        if (contentVersion != null && !contentVersion.equals(version.contentVersion()))
            throw new ApiException(HttpStatus.CONFLICT, "CONTENT_CONFLICT", "题目内容版本已变化，请刷新后重试");
        var progress = outlines.calculate(target.outline(), target.answerStateDeclared(), answer, reported);
        String receipt = drafts.save(userId, requestId, target.contextId(), qid, revision,
                json.writeValueAsString(answer), target.contentSha256(), saved -> {
                    if (mapper.update(target.contextId(), userId, qid, saved, encode(progress.outlineStates()),
                            encode(progress.answerState())) != 1) throw new IllegalStateException("草稿进度写入失败");
                    var response = json.createObjectNode().put("revision", saved);
                    response.set("draftProgress", json.valueToTree(progress));
                    return json.writeValueAsString(response);
                });
        var savedReceipt = json.readTree(receipt);
        long saved = savedReceipt.path("revision").asLong();
        var stored = savedReceipt.path("draftProgress");
        return new DraftSaved(saved, new State("draft", answer, null, saved,
                nullable(stored.path("answerState")), nullable(stored.path("outlineStates"))),
                stamps.stamp(target.contextId(), qid, saved, target.contentSha256(), target.extensionDigest()));
    }

    private String encode(JsonNode node) { return node == null ? null : json.writeValueAsString(node); }
    private static JsonNode nullable(JsonNode node) { return node.isMissingNode() || node.isNull() ? null : node; }
}
