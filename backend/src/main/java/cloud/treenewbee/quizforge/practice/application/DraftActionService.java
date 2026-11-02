package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.practice.application.PracticeViews.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP adaptation; the existing service owns the atomic draft and receipt writes. */
@Service
public class DraftActionService {
    private final PracticeReadService reads;
    private final IdempotentDraftService drafts;
    private final QuestionStamps stamps;
    private final JsonMapper json;
    private final DraftOutlineService outlines;

    public DraftActionService(PracticeReadService reads, IdempotentDraftService drafts, QuestionStamps stamps, JsonMapper json,
            DraftOutlineService outlines) {
        this.reads = reads; this.drafts = drafts; this.stamps = stamps; this.json = json;
        this.outlines = outlines;
    }

    public DraftResponse save(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode answer, String contentVersion) {
        return save(userId, collectionId, qid, requestId, revision, answer, contentVersion, null);
    }

    public DraftResponse save(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode answer, String contentVersion, JsonNode reported) {
        var prepared = reads.prepareDraft(userId, collectionId, qid);
        if (contentVersion != null && !contentVersion.equals(prepared.template().stamp().contentVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTENT_CONFLICT", "题目内容版本已变化，请刷新后重试");
        }
        String receipt = drafts.save(userId, requestId, prepared.contextId(), qid, revision,
                json.writeValueAsString(answer), prepared.contentSha256());
        long saved = json.readTree(receipt).path("revision").asLong();
        var progress = outlines.complete(userId, collectionId, qid, prepared.contextId(), requestId, saved, answer, reported, receipt);
        var template = prepared.template();
        // Do not re-read live state: replaying revision N after N+1 must return the saved answer/revision N.
        return new DraftResponse(saved, template.question(), new State("draft", answer, null, saved, progress.answerState(), progress.outlineStates()),
                template.draft(), stamps.stamp(prepared.contextId(), qid, saved, prepared.contentSha256(), prepared.extensionDigest()),
                template.extension(), template.features(), template.capabilities(), null, template.whiteboard());
    }
}
