package cloud.treenewbee.quizforge.practice.application;

/** 同步事务事件只登记任务或取消排队任务，不执行规则与模型。 */
public final class GradingEvents {
    private GradingEvents() {}
    public record Submitted(String userId, String contextId, String questionId) {}
    public record Invalidated(String userId, String contextId, String questionId) {}
}
