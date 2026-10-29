package cloud.treenewbee.quizforge.rules;

/**
 * rule-runner 服务不可用：连接失败、超时、过载（503）、内部错误（5xx）或本地并发已满。
 *
 * <p>不表示“规则拒绝”，那是正常结果（{@link RuleResult#rejected}）。调用方对所有不可用原因的处理相同：
 * 对外返回 503 {@code RULE_UNAVAILABLE}，前端保留输入稍后重试（协议第 8 节），因此不再细分原因。
 */
public class RuleRunnerException extends RuntimeException {

    public RuleRunnerException(String message) {
        super(message);
    }

    public RuleRunnerException(String message, Throwable cause) {
        super(message, cause);
    }
}
