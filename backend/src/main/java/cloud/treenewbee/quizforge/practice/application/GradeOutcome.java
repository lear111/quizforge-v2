package cloud.treenewbee.quizforge.practice.application;

import java.math.BigDecimal;

/** Rule output only; pending grading has a null score and absent outline states stay null. */
public record GradeOutcome(String resultJson, String projectedJson, BigDecimal score, BigDecimal maxScore,
        String gradingStatus, String outlineStatesJson) {}
