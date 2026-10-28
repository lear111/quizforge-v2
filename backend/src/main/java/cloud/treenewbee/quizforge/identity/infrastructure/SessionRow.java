package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;

/** Persistence representation of auth_session. Only the token hash is stored. */
public record SessionRow(
        byte[] tokenHash,
        String userId,
        Instant createdAt,
        Instant lastUsedAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        String userAgent) {
}
