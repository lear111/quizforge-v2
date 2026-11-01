package cloud.treenewbee.quizforge.identity.verification;

/** 人机验证（生产为 Cloudflare Turnstile）。 */
public interface CaptchaVerifier {

    /** 是否已配置可用。 */
    boolean ready();

    /** 前端渲染组件使用的类型：turnstile / fake / 空串（不可用）。 */
    String provider();

    /** 公开的 site key，未配置时为空串。 */
    String siteKey();

    /** 校验令牌，同时核对站点主机名与动作名；任何异常都视为未通过。 */
    boolean verify(String token, String remoteIp, String hostname, String action);
}
