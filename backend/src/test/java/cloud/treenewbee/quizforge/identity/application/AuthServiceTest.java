package cloud.treenewbee.quizforge.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.identity.domain.PasswordHasher;
import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

@Transactional
@Import(AuthServiceTest.FixedClockConfiguration.class)
class AuthServiceTest extends MySqlTestBase {

    private static final Instant NOW = Instant.parse("2026-10-28T12:34:56.789Z");
    private static final String EMAIL = "alice@example.com";
    private static final String NAME = "Alice";
    private static final String PASSWORD = "pass123";
    private static final String LEGACY_PASSWORD_HASH = "0123456789abcdef0123456789abcdef:a030f54ab468de25f56791bc2f20a38777208de097268203d8cfe5cc89839ec5d9e13cee45079bb4fc64db7a0f48bd7ec34a14ca582624b606838d9ddd6324c5";
    private static final String USER_AGENT = "test-agent";
    private static final String PASSWORD_ALGO = "scrypt-v1";

    @Autowired
    AuthService auth;

    @Autowired
    UserMapper users;

    @Autowired
    SessionMapper sessions;

    @Test
    void registerCreatesActiveSession() {
        var result = auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);

        assertThat(sessions.findActive(SessionToken.hash(result.token()), NOW)).hasValueSatisfying(session -> {
            assertThat(session.userId()).isEqualTo(result.userId());
            assertThat(session.createdAt()).isEqualTo(NOW);
            assertThat(session.lastUsedAt()).isEqualTo(NOW);
            assertThat(session.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
            assertThat(session.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        });
    }

    @Test
    void registerStoresPasswordHashAndAlgorithm() {
        var result = auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);

        assertThat(users.findById(result.userId())).hasValueSatisfying(user -> {
            assertThat(user.passwordHash()).isNotBlank().isNotEqualTo(PASSWORD);
            assertThat(user.passwordAlgo()).isEqualTo("scrypt-v1");
        });
    }

    @Test
    void registerThenLogin() {
        var result1 = auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);

        var result2 = auth.login(EMAIL, PASSWORD, USER_AGENT);

        assertThat(result2.token()).isNotEqualTo(result1.token());
        assertThat(result2.userId()).isEqualTo(result1.userId());

        assertThat(sessions.findActive(SessionToken.hash(result2.token()), NOW)).hasValueSatisfying(session -> {
            assertThat(session.createdAt()).isEqualTo(NOW);
            assertThat(session.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
            assertThat(session.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        });
    }

    @Test
    void emailIsNormalizedOnRegisterAndLogin() {
        var registered = auth.register("  Foo@Example.COM ", NAME, PASSWORD, USER_AGENT);
        var loggedIn = auth.login("foo@example.com", PASSWORD, USER_AGENT);

        assertThat(loggedIn.userId()).isEqualTo(registered.userId());
        assertThat(users.findById(registered.userId())).hasValueSatisfying(user ->
                assertThat(user.email()).isEqualTo("foo@example.com"));
    }

    @Test
    void loginNormalizesMixedCaseEmail() {
        var registered = auth.register("foo@example.com", NAME, PASSWORD, USER_AGENT);

        var loggedIn = auth.login("  Foo@Example.COM ", PASSWORD, USER_AGENT);

        assertThat(loggedIn.userId()).isEqualTo(registered.userId());
    }

    @Test
    void normalizeEmailRejectsNull() {
        assertThatThrownBy(() -> AuthService.normalizeEmail(null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\r\n"})
    void normalizeEmailRejectsEmptyOrWhitespace(String email) {
        assertThatThrownBy(() -> AuthService.normalizeEmail(email))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void registerDuplicateEmail() {
        auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);
        assertThatThrownBy(() -> auth.register(EMAIL, NAME, PASSWORD, USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("EMAIL_TAKEN");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void loginWrongPassword() {
        auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);
        assertThatThrownBy(() -> auth.login(EMAIL, "wrongpass", USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("邮箱或密码错误");
                });
    }

    @Test
    void loginNonexistentEmail() {
        assertThatThrownBy(() -> auth.login("nonexistent@example.com", PASSWORD, USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("邮箱或密码错误");
                });
    }

    @Test
    void loginNonexistentEmailRejectsEvenIfHasherReportsMatch() {
        UserMapper emptyUsers = mock(UserMapper.class);
        PasswordHasher matchingHasher = mock(PasswordHasher.class);
        when(emptyUsers.findByEmail(EMAIL)).thenReturn(Optional.empty());
        // 用户存在性必须独立于密码比较结果进行校验。
        when(matchingHasher.verify(PASSWORD, null)).thenReturn(true);
        AuthService isolatedAuth = new AuthService(emptyUsers, mock(SessionMapper.class), matchingHasher,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(TransactionTemplate.class));

        assertThatThrownBy(() -> isolatedAuth.login(EMAIL, PASSWORD, USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.getMessage()).isEqualTo("邮箱或密码错误");
                });
    }

    // 使用已知的旧版 scrypt 哈希验证兼容性。
    @Test
    void legacyUserCanLogin() {
        users.insert(new UserRow(
                Ids.uuidV7(),
                EMAIL,
                NAME,
                LEGACY_PASSWORD_HASH,
                PASSWORD_ALGO,
                false,
                NOW,
                NOW,
                0L
        ));

        var result = auth.login(EMAIL, PASSWORD, USER_AGENT);

        assertThat(sessions.findActive(SessionToken.hash(result.token()), NOW)).isPresent();
    }

    @Test
    void logoutInvalidatesSession() {
        var result = auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);

        auth.logout(result.token());

        assertThat(sessions.findActive(SessionToken.hash(result.token()), NOW)).isEmpty();
    }

    @Test
    void logoutWithMalformedTokenDoesNotThrow() {
        assertThatCode(() -> auth.logout("malformed-token")).doesNotThrowAnyException();
    }

    @Test
    void logoutWithNullTokenDoesNotThrow() {
        assertThatCode(() -> auth.logout(null)).doesNotThrowAnyException();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
