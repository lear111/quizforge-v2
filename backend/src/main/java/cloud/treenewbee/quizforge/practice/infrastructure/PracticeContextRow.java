package cloud.treenewbee.quizforge.practice.infrastructure;

/** Values read under the caller's context row lock. */
public record PracticeContextRow(String id, String currentRoundId, long progressVersion,
        String completionStatus, String lifecycleJobId) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public PracticeContextRow {}
    public PracticeContextRow(String id, String currentRoundId, long progressVersion) {
        this(id,currentRoundId,progressVersion,"active",null);
    }
}
