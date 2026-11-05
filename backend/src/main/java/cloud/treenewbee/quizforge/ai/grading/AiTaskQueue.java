package cloud.treenewbee.quizforge.ai.grading;

import java.util.Optional;

/** 领取在短事务中完成；将来可用其他队列实现替换。 */
public interface AiTaskQueue {
    Optional<AiGradingTask> claim();
}
