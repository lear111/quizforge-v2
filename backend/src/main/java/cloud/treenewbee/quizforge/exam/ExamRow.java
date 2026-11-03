package cloud.treenewbee.quizforge.exam;

import java.time.Instant;

public record ExamRow(String id, String userId, String collectionId, String contextId, String bankId,
    String status, Integer durationMinutes, Instant startedAt, Instant deadlineAt, Instant submittedAt,
    String roundId, String createRequestId, long version, String title) {
}
