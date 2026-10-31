package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;

public record PracticeRoundRow(String id, String userId, String collectionId, String contextId, String mode,
        String status, String collectionSnapshot, String summary, Instant createdAt, Instant updatedAt,
        Instant completedAt, Instant deletedAt) {}
