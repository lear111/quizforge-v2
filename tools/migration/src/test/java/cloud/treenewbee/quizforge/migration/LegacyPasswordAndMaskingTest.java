package cloud.treenewbee.quizforge.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyPasswordAndMaskingTest {

    // 详细设计 09 第 2.2 节的向量：由 Node crypto.scryptSync 生成，Python hashlib.scrypt 复算一致。
    private static final String VECTOR = "00112233445566778899aabbccddeeff:cfc7a534abad344268842ad61f529c79"
            + "397327fcf2f6bb1dd07e19e60b4e5ceaff127c1119f98f7ac7f461818db1665ff590bb457edf46c32441fecfa2dd2738";
    private static final String UNICODE_VECTOR = "00112233445566778899aabbccddeeff:7c2e44f1e717691b5e11e226043bbd79"
            + "d3088689339b28b7ff6c6596dcd68b2b6c3333edc48f4fd31d03c882547d76ea28a68ad67e704e31644814757ab2d68a";

    @Test
    void offlinePasswordCheckMatchesTheNodeVectors() {
        assertThat(LegacyPassword.matches("quizforge-legacy-1", VECTOR)).isTrue();
        assertThat(LegacyPassword.matches("密码测试😀abc", UNICODE_VECTOR)).isTrue();
        assertThat(LegacyPassword.matches("quizforge-legacy-2", VECTOR)).isFalse();
        assertThat(LegacyPassword.matches("quizforge-legacy-1", "not-a-hash")).isFalse();
    }

    @Test
    void reportMaskingHidesPersonalData() {
        assertThat(Masking.email("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(Masking.phone("+8613800138000")).isEqualTo("+86138****8000");
        assertThat(Masking.name("王小明")).isEqualTo("王*");
        assertThat(Masking.name("😀abc")).isEqualTo("😀*");
        assertThat(Masking.id("0123456789abcdef")).isEqualTo("01234567…");
    }

    @Test
    void identifierNormalizationMatchesV2() {
        assertThat(Verifier.normalize(" Alice@Example.COM ")).isEqualTo("alice@example.com");
        for (String phone : new String[] {"138 0013 8000", "+8613800138000", "8613800138000", "138-0013-8000"}) {
            assertThat(Verifier.normalize(phone)).isEqualTo("+8613800138000");
        }
    }

    @Test
    void collationKeyTreatsCaseAndAccentsAsEqual() {
        assertThat(AccountPlan.collationKey("José@Example.test")).isEqualTo(AccountPlan.collationKey("jose@example.test"));
    }
}
