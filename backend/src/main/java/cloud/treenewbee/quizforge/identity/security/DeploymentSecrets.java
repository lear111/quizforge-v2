package cloud.treenewbee.quizforge.identity.security;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 开发默认凭据只能用于本机假验证码模式，避免生产配置遗漏时静默启动。 */
@Component
public class DeploymentSecrets {
    public DeploymentSecrets(@Value("${qf.verification.mode}") String mode,
            @Value("${qf.security.allowed-origin}") String origin,
            @Value("${qf.rule-runner.token}") String runnerToken,
            @Value("${spring.datasource.password:}") String databasePassword) {
        boolean development = "fake".equals(mode) && localOrigin(origin);
        if (!development) {
            if (runnerToken == null || runnerToken.isBlank() || "quizforge-local-rule-token".equals(runnerToken)) {
                throw new IllegalStateException("正式配置必须设置独立的 QF_RULE_RUNNER_TOKEN");
            }
            if (databasePassword == null || databasePassword.isBlank() || "quizforge".equals(databasePassword)) {
                throw new IllegalStateException("正式配置必须设置独立的 QF_DB_PASSWORD");
            }
        }
    }

    private static boolean localOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.endsWith(".localhost") || host.endsWith(".test"));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
