package cloud.treenewbee.quizforge.practice.infrastructure;

/** Values read under the caller's context row lock. */
public record PracticeContextRow(String id, String currentRoundId, long progressVersion) {}
