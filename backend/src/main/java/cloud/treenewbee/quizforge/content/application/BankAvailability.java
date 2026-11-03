package cloud.treenewbee.quizforge.content.application;

import cloud.treenewbee.quizforge.content.infrastructure.BankMapper;
import org.springframework.stereotype.Service;

/** Approval is read live: revoking one extension affects the next availability query. */
@Service
public class BankAvailability {
    /**
     * 题库可用性（设计 04 第 6.2 节）：available 可以打开；pending 审核中；rejected 未通过（附原因）；
     * revoked 拓展已停用。不存到题库实例上，审核状态变化后下一个请求就生效。
     */
    public record State(String status, String reason) {
        public boolean available() { return "approved".equals(status); }
        public String message() {
            return switch (status) {
                case "approved" -> null;
                case "rejected" -> reason == null || reason.isBlank() ? "未通过" : "未通过：" + reason;
                case "revoked" -> "拓展已停用";
                default -> "审核中";
            };
        }
    }

    private final BankMapper banks;
    public BankAvailability(BankMapper banks) { this.banks = banks; }
    public boolean isAvailable(String bankId) { return banks.isAvailable(bankId); }
    public State state(String bankId) {
        var state = banks.reviewState(bankId);
        return state == null || state.status() == null ? new State("pending", null) : new State(state.status(), state.reason());
    }
}
