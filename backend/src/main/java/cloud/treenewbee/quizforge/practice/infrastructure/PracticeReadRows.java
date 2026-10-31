package cloud.treenewbee.quizforge.practice.infrastructure;

public final class PracticeReadRows {
    private PracticeReadRows() {}

    public record Collection(String id, String bankId, String title, String groupName,
            String description, String features, String listening, byte[] contentSha256, int questionCount) {}
    public record Summary(String id, String title, String outline, String listening,
            byte[] extensionDigest, String extensionId, String extensionVersion, String extensionName,
            String status, String answerState, String result, String outlineStates, Long revision, Integer outlineGroupIndex, String outlineGroupName) {}
    public record State(String status, String answer, String answerState, String result,
            String outlineStates, String projection, long revision) {}
}
