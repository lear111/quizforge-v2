package cloud.treenewbee.quizforge.admin.infrastructure;

import java.time.Instant;

public record ExtensionReviewRow(
        byte[] digest,
        String extensionId,
        String version,
        String status,
        String reviewer,
        Instant reviewedAt,
        String reason) {}
