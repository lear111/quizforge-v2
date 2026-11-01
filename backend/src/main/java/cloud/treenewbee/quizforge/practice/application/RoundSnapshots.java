package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.practice.application.PracticeReadService.PreparedDraft;
import cloud.treenewbee.quizforge.practice.application.PracticeViews.*;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Snapshots contain projected content only. The caller's transaction freezes the submitted state and round together. */
@Component
public class RoundSnapshots {
    private final JsonMapper json;
    private final PracticeReadMapper reads;
    private final QuestionStamps stamps;
    private final PracticeReadService practice;
    public RoundSnapshots(JsonMapper json, PracticeReadMapper reads, QuestionStamps stamps, PracticeReadService practice) {
        this.json = json; this.reads = reads; this.stamps = stamps; this.practice = practice;
    }
    public String collection(String userId, String collectionId) { return practice.collectionSnapshot(userId, collectionId); }
    public String question(PreparedDraft prepared, JsonNode answer, GradeOutcome outcome) {
        var template = prepared.template(); var question = template.question();
        var saved = reads.state(prepared.contextId(), question.id(), prepared.userId()).orElseThrow();
        var state = new State("submitted", answer,
                SubmissionResults.result(json, outcome.resultJson(), outcome.score(), outcome.maxScore(), outcome.gradingStatus()),
                saved.revision(), null, outcome.outlineStatesJson() == null ? null : json.readTree(outcome.outlineStatesJson()));
        return json.writeValueAsString(new QuestionResponse(new Question(question.id(), question.title(),
                json.readTree(outcome.projectedJson()), question.outlineGroupIndex(), question.outlineGroupName()), state,
                template.draft(), stamps.stamp(prepared.contextId(), question.id(), saved.revision(), prepared.contentSha256(), prepared.extensionDigest()),
                template.extension(), template.features(), new Capabilities(false, false, false, false, false, false), null));
    }
}
