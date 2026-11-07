package cloud.treenewbee.quizforge.identity.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class DeploymentSecretsTest {
    @Test
    void developmentDefaultsRequireFakeModeAndLocalOrigin() {
        assertThatCode(() -> new DeploymentSecrets("fake", "http://localhost:8088",
                "quizforge-local-rule-token", "quizforge")).doesNotThrowAnyException();
        for (String mode : new String[] {"fake", "provider"}) {
            assertThatThrownBy(() -> new DeploymentSecrets(mode, "https://qf.example.com",
                    "quizforge-local-rule-token", "independent-password"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("QF_RULE_RUNNER_TOKEN");
        }
        assertThatThrownBy(() -> new DeploymentSecrets("provider", "http://localhost:8088",
                "quizforge-local-rule-token", "independent-password")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRejectsEmptyOrDefaultSecretsWithoutPrintingThem() {
        for (String token : new String[] {"", " ", "quizforge-local-rule-token"}) {
            assertThatThrownBy(() -> new DeploymentSecrets("provider", "https://qf.example.com", token, "independent-password"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("正式配置必须设置独立的 QF_RULE_RUNNER_TOKEN");
        }
        for (String password : new String[] {"", " ", "quizforge"}) {
            assertThatThrownBy(() -> new DeploymentSecrets("provider", "https://qf.example.com", "independent-token", password))
                    .isInstanceOf(IllegalStateException.class).hasMessage("正式配置必须设置独立的 QF_DB_PASSWORD");
        }
        assertThatCode(() -> new DeploymentSecrets("provider", "https://qf.example.com", "independent-token", "independent-password"))
                .doesNotThrowAnyException();
    }
}
