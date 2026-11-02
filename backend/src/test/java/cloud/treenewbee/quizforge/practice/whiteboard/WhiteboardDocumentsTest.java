package cloud.treenewbee.quizforge.practice.whiteboard;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

class WhiteboardDocumentsTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WhiteboardDocuments docs = new WhiteboardDocuments(json);
    @Test void stageFollowsSubmissionAndCompletionInsteadOfUserChoice() {
        WhiteboardDocuments.requireWritableLayer("draft",null,false);
        WhiteboardDocuments.requireWritableLayer("draft","in_progress",false);
        assertThatThrownBy(() -> WhiteboardDocuments.requireWritableLayer("notes","in_progress",false))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("WHITEBOARD_NOTES_UNAVAILABLE"));
        for (String status : new String[]{"in_progress","completed"}) {
            WhiteboardDocuments.requireWritableLayer("notes",status,true);
            assertThatThrownBy(() -> WhiteboardDocuments.requireWritableLayer("draft",status,true))
                    .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("WHITEBOARD_DRAFT_LOCKED"));
        }
        WhiteboardDocuments.requireWritableLayer("notes","completed",false);
        assertThat(WhiteboardDocuments.draftLocked("completed",false)).isTrue();
    }
    @Test void notesCanBeEditedOrErasedButCannotReuseFrozenStrokeIds() {
        var frozen=docs.empty();((tools.jackson.databind.node.ArrayNode)frozen.get("strokes")).add(stroke("original"));
        var notes=docs.empty();((tools.jackson.databind.node.ArrayNode)notes.get("strokes")).add(stroke("note"));
        docs.requireSeparateLayers(frozen,notes);
        ((tools.jackson.databind.node.ObjectNode)notes.path("strokes").get(0)).put("width",6);
        docs.requireSeparateLayers(frozen,docs.normalize(notes));
        docs.requireSeparateLayers(frozen,docs.empty());
        assertThatThrownBy(() -> docs.requireSeparateLayers(frozen,frozen)).isInstanceOf(ApiException.class);
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
