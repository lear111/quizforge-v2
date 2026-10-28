package cloud.treenewbee.quizforge.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private static final String LEGACY_ASCII = "0123456789abcdef0123456789abcdef:a030f54ab468de25f56791bc2f20a38777208de097268203d8cfe5cc89839ec5d9e13cee45079bb4fc64db7a0f48bd7ec34a14ca582624b606838d9ddd6324c5";

    private static final String LEGACY_CHINESE = "0123456789abcdef0123456789abcdef:99a1a31b681a5844309ad7fea2021fc302f12d3a68a7484e2bae13113b456e60766bc80ecd3ab6db57df42c6708db4cb8aba841ef13fcbc7da8b0a1ce8af3839";

    private static final String LEGACY_LONE_SURROGATE = "0123456789abcdef0123456789abcdef:c4ca071d841ad7302dd93aebef65b1e08d8e57da3e6e54cdb0790c1fe41e786507d2ffc618cd38952ad7f40723728029fb90ffc2064a5b1e9ff1ee5066edb957";

    private final PasswordHasher hasher = new PasswordHasher();

    // 验证使用旧版哈希算法生成的密码哈希值
    @Test
    void verifiesLegacyAsciiHash() {
        assertThat(hasher.verify("pass123", LEGACY_ASCII)).isTrue();
    }

    // 验证使用旧版哈希算法生成的包含中文字符的密码哈希值
    @Test
    void verifiesLegacyChineseHash() {
        assertThat(hasher.verify("密码abc123", LEGACY_CHINESE)).isTrue();
    }

    // 验证使用旧版哈希算法生成的包含孤立代理项的密码哈希值
    @Test
    void verifiesLegacyLoneSurrogateHash() {
        assertThat(hasher.verify("pass\uD800word", LEGACY_LONE_SURROGATE)).isTrue();
    }

    // 密码错误时返回 false
    @Test
    void rejectsWrongPassword() {
        assertThat(hasher.verify("wrongpass", LEGACY_ASCII)).isFalse();
    }

    // hash(null) 应该抛出 NullPointerException
    @Test
    void hashRejectsNullPassword() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(NullPointerException.class);
    }

    // hash 生成的值能被 verify 校验通过
    @Test
    void hashAndVerify() {
        String password = "pass123";
        String stored = hasher.hash(password);
        assertThat(hasher.verify(password, stored)).isTrue();
    }

    // 同一个密码 hash 两次，结果不同
    @Test
    void hashProducesDifferentResults() {
        String password = "pass123";
        String stored1 = hasher.hash(password);
        String stored2 = hasher.hash(password);
        assertThat(stored1).isNotEqualTo(stored2);
    }

    // hash 的结果为 32 位 hex、冒号、128 位 hex
    @Test
    void hashProducesValidFormat() {
        String password = "pass123";
        String stored = hasher.hash(password);
        assertThat(stored).matches("[a-f0-9]{32}:[a-f0-9]{128}");
    }

    // 存储值格式错误时返回 false，至少测 "abc" 和 null 两种
    @Test
    void verifyRejectsMalformedStoredValues() {
        assertThat(hasher.verify("pass123", "abc")).isFalse();
        assertThat(hasher.verify("pass123", null)).isFalse();
    }

    // 密码为 null 时返回 false
    @Test
    void verifyRejectsNullPassword() {
        String stored = hasher.hash("pass123");
        assertThat(hasher.verify(null, stored)).isFalse();
    }
}