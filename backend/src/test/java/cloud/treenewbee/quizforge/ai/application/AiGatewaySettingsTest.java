package cloud.treenewbee.quizforge.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** 模型接入配置的启动检查与计费公式（设计 08 第 0、2.4 节）。 */
class AiGatewaySettingsTest {

    private static AiGatewaySettings settings(String baseUrl, Duration callTimeout, Duration lease) {
        return new AiGatewaySettings(baseUrl, "qwen-vl-plus", callTimeout, Duration.ofSeconds(300), 8192, 2, 8, lease);
    }

    @Test
    void callTimeoutTimesKeyAttemptsMustFitInsideTheTaskLease() {
        // 40 × 3 + 10 = 130 < 150：通过；46 × 3 + 10 = 148 < 150：通过；47 × 3 + 10 = 151：启动失败。
        settings("https://dashscope.aliyuncs.com/compatible-mode/v1", Duration.ofSeconds(40), Duration.ofSeconds(150));
        settings("https://dashscope.aliyuncs.com/compatible-mode/v1", Duration.ofSeconds(46), Duration.ofSeconds(150));
        assertThatThrownBy(() -> settings("https://dashscope.aliyuncs.com/compatible-mode/v1", Duration.ofSeconds(47),
                Duration.ofSeconds(150))).isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
    }

    @Test
    void upstreamMustBeHttpsExceptForLocalTestServers() {
        assertThat(settings("https://dashscope.aliyuncs.com/compatible-mode/v1/", Duration.ofSeconds(40),
                Duration.ofSeconds(150)).baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        settings("http://127.0.0.1:9000/v1", Duration.ofSeconds(40), Duration.ofSeconds(150));
        for (String bad : new String[] {"http://dashscope.aliyuncs.com/v1", "https://user:pw@example.com/v1",
                "https://example.com/v1?x=1", "ftp://example.com", "not a url"}) {
            assertThatThrownBy(() -> settings(bad, Duration.ofSeconds(40), Duration.ofSeconds(150))).as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void costRoundsUpPerMillionTokensWithAMinimumOfOneUnit() {
        assertThat(AiGateway.cost(1000, 500, 30_000, 90_000)).isEqualTo(75);
        assertThat(AiGateway.cost(1, 1, 30_000, 90_000)).isEqualTo(1);
        assertThat(AiGateway.cost(0, 0, 30_000, 90_000)).isEqualTo(1);
        assertThat(AiGateway.cost(1_000_001, 0, 1, 0)).isEqualTo(2);
        assertThat(AiGateway.cost(1_000_000, 0, 1, 0)).isEqualTo(1);
        assertThat(AiGateway.cost(100_000, 8192, 0, 0)).isEqualTo(1);
    }
}
