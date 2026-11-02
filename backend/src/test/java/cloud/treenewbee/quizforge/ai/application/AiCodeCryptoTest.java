package cloud.treenewbee.quizforge.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/** 兑换码完整码的加密与启动检查（设计 08 第 1.3 节）。 */
class AiCodeCryptoTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void missingKeyFailsStartupInProviderModeOrForPublicOrigins() {
        assertThatThrownBy(() -> new AiCodeCrypto("", "provider", "http://localhost:8088"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QF_AI_MASTER_KEY");
        assertThatThrownBy(() -> new AiCodeCrypto("", "fake", "https://quizforge.example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiCodeCrypto(Base64.getEncoder().encodeToString(new byte[16]), "provider",
                "https://quizforge.example.com")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiCodeCrypto("not base64!", "provider", "https://quizforge.example.com"))
                .isInstanceOf(IllegalStateException.class);
        // 只有本机或 .test 来源的 fake 模式允许开发密钥。
        new AiCodeCrypto("", "fake", "http://localhost:8088");
        new AiCodeCrypto("", "fake", "https://qf.example.test");
        new AiCodeCrypto(KEY, "provider", "https://quizforge.example.com");
    }

    @Test
    void ciphertextIsAuthenticatedAndRandomized() {
        AiCodeCrypto crypto = new AiCodeCrypto(KEY, "provider", "https://quizforge.example.com");
        String code = "AI-ABCD-EFGH-JKMN-PQRS";
        String first = crypto.encrypt(code);
        assertThat(first).isNotEqualTo(crypto.encrypt(code)).doesNotContain("ABCD");
        assertThat(crypto.decrypt(first)).isEqualTo(code);
        byte[] tampered = Base64.getDecoder().decode(first);
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(Base64.getEncoder().encodeToString(tampered)))
                .isInstanceOf(IllegalStateException.class);
        AiCodeCrypto other = new AiCodeCrypto(Base64.getEncoder().encodeToString(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}),
                "provider", "https://quizforge.example.com");
        assertThatThrownBy(() -> other.decrypt(first)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redeemInputIsNormalizedLikeTheLegacyPlatform() {
        assertThat(RedeemCodeService.normalize("ai-abcd-efgh-jkmn-pqrs")).isEqualTo("ABCDEFGHJKMNPQRS");
        assertThat(RedeemCodeService.normalize("﻿ AI ABCD EFGH JKMN PQRS ")).isEqualTo("ABCDEFGHJKMNPQRS");
        assertThat(RedeemCodeService.normalize("O0IL-0000-0000-0000")).isEqualTo("0011000000000000");
        assertThat(RedeemCodeService.normalize("AI-ABCD-EFGH-JKMN-PQRU")).isNull();
        assertThat(RedeemCodeService.normalize("AI-ABCD-EFGH-JKMN")).isNull();
        assertThat(RedeemCodeService.normalize(null)).isNull();
        assertThat(RedeemCodeService.normalize("A".repeat(65))).isNull();
    }
}
