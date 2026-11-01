package cloud.treenewbee.quizforge.identity.verification;

/** 验证码用途。不同用途的验证码互相隔离，不能混用（AUTH_POLICY.md）。 */
public enum VerificationPurpose {
    REGISTER("注册", "register"),
    LOGIN("登录", "login"),
    BIND("绑定", "bind"),
    RESET_PASSWORD("找回密码", "reset");

    private final String label;
    private final String key;

    VerificationPurpose(String label, String key) {
        this.label = label;
        this.key = key;
    }

    /** 邮件标题、短信模板中使用的中文用途。 */
    public String label() {
        return label;
    }

    /** 参与验证码摘要计算的用途前缀。 */
    public String key() {
        return key;
    }
}
