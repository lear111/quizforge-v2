package cloud.treenewbee.quizforge.ai.grading;

import java.time.Instant;

/** inputJson 与 checkpoint 只供服务器恢复任务，不能整体序列化为 HTTP 响应。 */
public record AiGradingTask(String id, String userId, String kind, String collectionId, String contextId,
        String questionId, String targetId, String roundId, int submissionGeneration, int gradingGeneration,
        byte[] answerSha256, byte[] contentSha256, String status, int priority, boolean automatic,
        int attempts, int modelCalls, boolean repairUsed, Instant nextAttemptAt, Instant lockedUntil,
        String inputJson, String checkpoint, String candidate, String candidateVersion, long cost,
        String errorCode, String errorMessage, Instant createdAt, Instant updatedAt) {}
