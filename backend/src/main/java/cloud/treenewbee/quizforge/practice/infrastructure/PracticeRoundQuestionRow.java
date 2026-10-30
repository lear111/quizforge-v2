package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;

public record PracticeRoundQuestionRow(String roundId, String questionId, String userId, String payload,
        byte[] extensionDigest, Instant updatedAt) {}
