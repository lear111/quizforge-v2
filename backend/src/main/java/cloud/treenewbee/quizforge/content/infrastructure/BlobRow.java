package cloud.treenewbee.quizforge.content.infrastructure;

import java.time.Instant;

public record BlobRow(
        byte[] sha256,
        long size,
        String mime,
        Instant createdAt) {}
