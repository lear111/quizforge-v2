package cloud.treenewbee.quizforge.practice.resources;

import java.time.Instant;

public record UserResourceRow(String userId, byte[] blobSha256, String mime, int size, Instant createdAt) {}
