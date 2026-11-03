package cloud.treenewbee.quizforge.content.infrastructure;

import java.time.Instant;

public record BankRow(
        String id,
        byte[] contentSha256,
        String bankUuid,
        String sourceTitle,
        String description,
        int formatVersion,
        String features,
        String listening,
        int questionCount,
        Instant createdAt) {}
