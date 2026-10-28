package cloud.treenewbee.quizforge.content.infrastructure;

import java.math.BigDecimal;

public record QuestionRow(
        String bankId,
        String questionId,
        int position,
        String title,
        byte[] extensionDigest,
        String outline,
        String listening,
        String data,
        BigDecimal maxScore,
        byte[] contentSha256) {}
