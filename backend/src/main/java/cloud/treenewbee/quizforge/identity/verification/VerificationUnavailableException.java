package cloud.treenewbee.quizforge.identity.verification;

/** 发送服务未配置、拒绝或超时。消息只用于日志，不返回给客户端。 */
public class VerificationUnavailableException extends RuntimeException {

    public VerificationUnavailableException(String message) {
        super(message);
    }

    public VerificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
