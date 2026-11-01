package cloud.treenewbee.quizforge.practice.application;

import java.util.List;
import java.util.Map;
import cloud.treenewbee.quizforge.practice.application.QuestionStamps.Stamp;
import tools.jackson.databind.JsonNode;

public final class PracticeViews {
    private PracticeViews() {}
    public record Extension(String id, String version, String name, String pageId) {}
    public record State(String status, JsonNode answer, JsonNode result, long revision,
            JsonNode answerState, JsonNode outlineStates) {}
    public record QuestionSummary(String id, String title, Extension type, String outlineLabel,
            JsonNode outlineItems, JsonNode listening, Integer outlineGroupIndex, String outlineGroupName) {}
    public record Availability(boolean available, String code, String message) {}
    public record Bank(String id, String kind, String title, String group, int questionCount, Availability availability) {}
    public record Catalog(List<Bank> banks, List<Object> extensions, Map<String, List<String>> groups,
            boolean canManage, boolean developmentEnabled) {}
    public record Overview(String id, String kind, String title, String revision, String description,
            JsonNode features, JsonNode listening, List<QuestionSummary> questions, Map<String, State> states,
            Availability availability, boolean readOnly) {}
    public record Question(String id, String title, JsonNode data, Integer outlineGroupIndex, String outlineGroupName) {}
    public record Capabilities(boolean canSave, boolean canSubmit, boolean canRetry, boolean canReview, boolean canAiGrade, boolean canWhiteboard) {}
    public record QuestionResponse(Question question, State state, JsonNode draft, Stamp stamp,
            Extension extension, JsonNode features, Capabilities capabilities, Object aiTask,
            cloud.treenewbee.quizforge.practice.whiteboard.WhiteboardViews.View whiteboard) {
        public QuestionResponse(Question question, State state, JsonNode draft, Stamp stamp, Extension extension,
                JsonNode features, Capabilities capabilities, Object aiTask) {
            this(question,state,draft,stamp,extension,features,capabilities,aiTask,null);
        }
    }
    public record Unchanged(boolean unchanged, Stamp stamp) {}
    public record DraftResponse(long revision, Question question, State state, JsonNode draft, Stamp stamp,
            Extension extension, JsonNode features, Capabilities capabilities, Object aiTask,
            cloud.treenewbee.quizforge.practice.whiteboard.WhiteboardViews.View whiteboard) {
        public DraftResponse(long revision, Question question, State state, JsonNode draft, Stamp stamp, Extension extension,
                JsonNode features, Capabilities capabilities, Object aiTask) {
            this(revision,question,state,draft,stamp,extension,features,capabilities,aiTask,null);
        }
    }
}
