package cloud.treenewbee.quizforge.identity.verification;

import cloud.treenewbee.quizforge.identity.domain.Contact;

/** 发送邮件或短信验证码。生产实现对接旧平台的服务商；开发与测试使用 {@link FakeVerification}。 */
public interface VerificationSender {

    /** 该通道的发送服务是否已完整配置。 */
    boolean available(Contact.Channel channel);

    /** 参与验证码、地址和 IP 摘要计算的密钥；未配置时返回空串。 */
    String pepper(Contact.Channel channel);

    /**
     * 发送一条验证码。调用方不在数据库事务内调用（慢操作在事务外）。
     *
     * @throws VerificationUnavailableException 服务商拒绝、超时或未配置
     */
    void send(Contact contact, String code, VerificationPurpose purpose, String requestId);
}
