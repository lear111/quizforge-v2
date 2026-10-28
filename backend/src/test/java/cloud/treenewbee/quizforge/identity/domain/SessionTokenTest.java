package cloud.treenewbee.quizforge.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class SessionTokenTest {

    // generate() 的结果正好 43 个字符，且只包含 [A-Za-z0-9_-]
    @Test
    void generateProducesWellFormedToken() {
        String token = SessionToken.generate();
        assertThat(token).hasSize(43);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
    }

    // 两次 generate() 的结果不同
    @Test
    void generateProducesDifferentTokens() {
        String token1 = SessionToken.generate();
        String token2 = SessionToken.generate();
        assertThat(token1).isNotEqualTo(token2);
    }

    // generate() 的结果能通过 isWellFormed
    @Test
    void generateProducesWellFormedTokenAccordingToIsWellFormed() {
        String token = SessionToken.generate();
        assertThat(SessionToken.isWellFormed(token)).isTrue();
    }

    // isWellFormed 拒绝：null、空字符串、42 个字符、44 个字符、含有 + 或 = 的字符串
    @Test
    void isWellFormedRejectsInvalidTokens() {
        assertThat(SessionToken.isWellFormed(null)).isFalse();
        assertThat(SessionToken.isWellFormed("")).isFalse();
        assertThat(SessionToken.isWellFormed("A".repeat(42))).isFalse();
        assertThat(SessionToken.isWellFormed("A".repeat(44))).isFalse();
        assertThat(SessionToken.isWellFormed("A".repeat(42) + "+")).isFalse();
        assertThat(SessionToken.isWellFormed("A".repeat(42) + "=")).isFalse();
    }

    // hash 返回 32 个字节
    @Test
    void hashProduces32Bytes() {
        String token = SessionToken.generate();
        byte[] hash = SessionToken.hash(token);
        assertThat(hash).hasSize(32);
    }

    // 同一个令牌 hash 两次，结果相同；不同令牌的 hash 结果不同
    @Test
    void hashIsDeterministic() {
        String token1 = SessionToken.generate();
        String token2 = SessionToken.generate();
        byte[] hash1a = SessionToken.hash(token1);
        byte[] hash1b = SessionToken.hash(token1);
        byte[] hash2 = SessionToken.hash(token2);

        assertThat(hash1a).isEqualTo(hash1b);
        assertThat(hash1a).isNotEqualTo(hash2);
    }

    // "abc" 的 SHA-256 与标准测试向量一致
    @Test
    void hashProducesExpectedResult() {
        String token = "abc";
        byte[] expected = HexFormat.of()
                .parseHex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        byte[] actual = SessionToken.hash(token);
        assertThat(actual).isEqualTo(expected);
    }

    // hash(null) 应该抛出 NullPointerException
    @Test
    void hashRejectsNullToken() {
        assertThatThrownBy(() -> SessionToken.hash(null))
                .isInstanceOf(NullPointerException.class);
    }
}