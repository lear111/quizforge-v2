package cloud.treenewbee.quizforge.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import cloud.treenewbee.quizforge.shared.id.Ids;

final class MapperFixtures {
    static final Instant NOW = Instant.parse("2026-10-27T12:34:56.789Z");

    private MapperFixtures() {
    }

    static UserRow user() {
        String id = Ids.uuidV7();
        return new UserRow(id, id + "@example.test", "测试用户", "salt:hash", "scrypt-v1",
                true, NOW.minusSeconds(60), NOW, 7L);
    }

    static SessionRow session(String userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(Ids.uuidV7().getBytes(StandardCharsets.US_ASCII));
            return new SessionRow(hash, userId, NOW.minusSeconds(60), NOW.minusSeconds(1),
                    NOW.plusSeconds(60), NOW.plusSeconds(3600), "QuizForge 测试客户端");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
