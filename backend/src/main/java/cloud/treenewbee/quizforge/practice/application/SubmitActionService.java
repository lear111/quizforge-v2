package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.practice.application.PracticeViews.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP adaptation only: SubmitService owns grading, optimistic concurrency and the receipt transaction. */
@Service
public class SubmitActionService {
    private final PracticeReadService reads;
    private final SubmitService submits;
    private final JsonMapper json;
    public SubmitActionService(PracticeReadService reads, SubmitService submits, JsonMapper json) {
        this.reads = reads; this.submits = submits; this.json = json;
    }
    public DraftResponse submit(String userId, String collectionId, String qid, String requestId, long revision,
            JsonNode answer, String contentVersion) {
        reads.validateContentVersion(userId, collectionId, qid, contentVersion);
        var receipt = json.readTree(submits.submit(userId, requestId, collectionId, qid, revision, answer));
        if (receipt.has("question")) return json.treeToValue(receipt, DraftResponse.class);
        // Compatibility for receipts created before complete responses were stored.
        long saved = receipt.path("revision").asLong();
        // A submitted read uses the persisted projection; neither first response nor receipt replay re-grades.
        var response = (QuestionResponse) reads.question(userId, collectionId, qid, null);
        var state = response.state();
        return new DraftResponse(saved, response.question(), new State(state.status(), answer, state.result(), saved,
                state.answerState(), state.outlineStates()), response.draft(),
                new QuestionStamps.Stamp(saved, response.stamp().contentVersion(), response.stamp().packageVersion()),
                response.extension(), response.features(), response.capabilities(), response.aiTask(),response.whiteboard());
    }
}
