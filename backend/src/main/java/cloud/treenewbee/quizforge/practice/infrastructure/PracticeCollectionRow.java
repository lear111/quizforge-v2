package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;

public record PracticeCollectionRow(
        String id,
        String userId,
        String bankId,
        String title,
        String groupName,
        String source,
        String legacyRouteId,
        String sourcePackId,
        String sourceBankKey,
        Integer sourceRevision,
        String updateStatus,
        Instant createdAt,
        Instant deletedAt) {}
