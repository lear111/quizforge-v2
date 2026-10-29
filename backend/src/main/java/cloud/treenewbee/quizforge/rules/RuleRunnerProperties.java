package cloud.treenewbee.quizforge.rules;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** rule-runner 的连接配置，来自 application.yml 的 qf.rule-runner.*（rule-runner 协议第 2、7 节）。 */
@ConfigurationProperties(prefix = "qf.rule-runner")
public record RuleRunnerProperties(
        URI baseUrl,
        String token,
        int maxConcurrency,
        Duration defaultDeadline) {
}
