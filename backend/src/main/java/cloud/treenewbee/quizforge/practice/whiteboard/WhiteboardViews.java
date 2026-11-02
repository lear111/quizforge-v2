package cloud.treenewbee.quizforge.practice.whiteboard;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WhiteboardViews {
    public record View(JsonNode draft, JsonNode notes, long revision, boolean draftLocked) {}
    private final WhiteboardMapper mapper;
    private final WhiteboardDocuments documents;
    private final JsonMapper json;
    public WhiteboardViews(WhiteboardMapper mapper, WhiteboardDocuments documents, JsonMapper json) {
        this.mapper=mapper; this.documents=documents; this.json=json;
    }
    public View read(String contextId,String qid,String userId,String roundStatus) {
        return read(contextId,qid,userId,roundStatus,false);
    }
    public View read(String contextId,String qid,String userId,String roundStatus,boolean submitted) {
        return view(mapper.find(contextId,qid,userId).orElse(null),roundStatus,submitted);
    }
    public View view(WhiteboardMapper.Row row,String roundStatus,boolean submitted) {
        return new View(row==null?documents.empty():json.readTree(row.draft()),
                row==null||row.notes()==null?documents.empty():json.readTree(row.notes()),
                row==null?0:row.revision(),WhiteboardDocuments.draftLocked(roundStatus,submitted));
    }
}
