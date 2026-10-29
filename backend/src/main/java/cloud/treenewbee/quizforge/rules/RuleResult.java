package cloud.treenewbee.quizforge.rules;

import tools.jackson.databind.JsonNode;

/**
 * 一次规则调用的业务结果（rule-runner 协议 3.2 节）。
 *
 * <p>“规则拒绝”（{@code ok:false}，例如作答不合法）是正常的业务结果，用 {@link #rejected} 表示，
 * 由调用方决定返回 400 还是 422；它是确定的，永远不重试。rule-runner 自身不可用不在这里表示，
 * 而是抛出 {@link RuleRunnerException}。
 */
public record RuleResult(boolean ok, JsonNode data, String code) {

    public static RuleResult success(JsonNode data) {
        return new RuleResult(true, data, null);
    }

    public static RuleResult rejected(String code) {
        return new RuleResult(false, null, code);
    }
}
