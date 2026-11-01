package cloud.treenewbee.quizforge.identity.verification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;

import org.junit.jupiter.api.Test;

/** provider 模式下验证码摘要密钥缺失时启动失败。 */
class VerificationConfigTest {

    private static final String PEPPER = "p".repeat(VerificationProperties.MIN_PEPPER_LENGTH);
    private static final String ORIGIN = "https://quizforge.example.com";

    @Test
    void providerModeRequiresBothPeppers() {
        for (String[] peppers : new String[][] {{"", ""}, {PEPPER, ""}, {"", PEPPER}, {PEPPER, "short"}}) {
            assertThatThrownBy(() -> create("provider", peppers[0], peppers[1], ORIGIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QF_EMAIL_CODE_PEPPER");
        }
        assertThatCode(() -> create("provider", PEPPER, PEPPER, ORIGIN)).doesNotThrowAnyException();
    }

    @Test
    void fakeModeNeedsNoPepperButOnlyRunsForLocalOrigins() {
        assertThatCode(() -> create("fake", "", "", "http://localhost:8088")).doesNotThrowAnyException();
        assertThatThrownBy(() -> create("fake", "", "", ORIGIN)).isInstanceOf(IllegalStateException.class);
    }

    private static void create(String mode, String emailPepper, String smsPepper, String origin) {
        var properties = new VerificationProperties(mode, emailPepper, smsPepper, 100, 100, 5, 20, null, null, null);
        new VerificationConfig().verificationServices(properties, Clock.systemUTC(), origin);
    }
}
