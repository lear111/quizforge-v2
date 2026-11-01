package cloud.treenewbee.quizforge.practice.whiteboard;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

class WhiteboardDocumentsTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WhiteboardDocuments docs = new WhiteboardDocuments(json);
    @Test void submittedQuestionsRemainWritableButCompletedPracticeLocksOnlyDraft() {
        WhiteboardDocuments.requireDraftWritable("in_progress");
        WhiteboardDocuments.requireDraftWritable(null);
        assertThatThrownBy(() -> WhiteboardDocuments.requireDraftWritable("completed"))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("WHITEBOARD_DRAFT_LOCKED"));
        var old = docs.empty(); var next = old.deepCopy();
        ((tools.jackson.databind.node.ArrayNode)next.get("strokes")).add(stroke("one"));
        docs.requireAppendOnly(old,next); // Completion does not disable appending notes.
    }
    @Test void existingNotesCannotBeErasedModifiedOrReordered() {
        var old=docs.empty();((tools.jackson.databind.node.ArrayNode)old.get("strokes")).add(stroke("one"));
        var next=old.deepCopy();((tools.jackson.databind.node.ArrayNode)next.get("strokes")).add(stroke("two"));
        docs.requireAppendOnly(old,next);
        assertThatThrownBy(()->docs.requireAppendOnly(old,docs.empty())).isInstanceOf(ApiException.class);
        var changed=old.deepCopy();((tools.jackson.databind.node.ObjectNode)changed.path("strokes").get(0)).put("width",6);
        assertThatThrownBy(()->docs.requireAppendOnly(old,changed)).isInstanceOf(ApiException.class);
    }
    @Test void validatesStrokeShapeDuplicateIdsAndFiniteCoordinates() {
        var doc=docs.empty();((tools.jackson.databind.node.ArrayNode)doc.get("strokes")).add(stroke("one"));
        assertThat(docs.normalize(doc)).isEqualTo(doc);
        ((tools.jackson.databind.node.ArrayNode)doc.get("strokes")).add(stroke("one"));
        assertThatThrownBy(()->docs.normalize(doc)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->docs.normalize(json.readTree("{\"schemaVersion\":1,\"strokes\":[],\"viewport\":{}}"))).isInstanceOf(ApiException.class);
    }
    private tools.jackson.databind.JsonNode stroke(String id) {
        return json.readTree("{\"id\":\""+id+"\",\"color\":\"#334155\",\"width\":3,\"points\":[{\"x\":10,\"y\":20,\"pressure\":0.5}]}");
    }
}
