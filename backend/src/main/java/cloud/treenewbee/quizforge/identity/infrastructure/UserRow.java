package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;

/** Persistence representation of account_user; email normalization belongs to the caller. */
public record UserRow(
        String id,
        String email,
        String name,
        String passwordHash,
        String passwordAlgo,
        boolean isAdmin,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
