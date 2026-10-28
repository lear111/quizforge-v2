package cloud.treenewbee.quizforge.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;


@Transactional   // 每个测试结束后回滚，测试之间互不影响
class AuthServiceTest extends MySqlTestBase {

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

        assertThat(sessions.findActive(SessionToken.hash(result.token()), Instant.now())).isPresent();
    }

    // 注册后，用同一个邮箱和密码能登录
    @Test
    void registerThenLogin() {
        var result1 = auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);

        var result2 = auth.login(EMAIL, PASSWORD, USER_AGENT);

        assertThat(result2.token()).isNotEqualTo(result1.token());
        assertThat(result2.userId()).isEqualTo(result1.userId());

        assertThat(sessions.findActive(SessionToken.hash(result2.token()), Instant.now())).isPresent();
    }

    // 邮箱规范化：用 " Foo@Example.COM " 注册，用 "foo@example.com" 能登录
    @Test
    void emailIsNormalizedOnRegisterAndLogin() {
        var registered = auth.register("  Foo@Example.COM ", NAME, PASSWORD, USER_AGENT);
        var loggedIn = auth.login("foo@example.com", PASSWORD, USER_AGENT);

        assertThat(loggedIn.userId()).isEqualTo(registered.userId());
    }

    // 重复注册同一个邮箱：错误码为 EMAIL_TAKEN
    @Test
    void registerDuplicateEmail() {
        auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);
        assertThatThrownBy(() -> auth.register(EMAIL, NAME, PASSWORD, USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("EMAIL_TAKEN");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    // 密码错误：错误码为 INVALID_CREDENTIALS
    @Test
    void loginWrongPassword() {
        auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);
        assertThatThrownBy(() -> auth.login(EMAIL, "wrongpass", USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    // 邮箱不存在：错误码为 INVALID_CREDENTIALS，提示信息和密码错误时完全相同
    @Test
    void loginNonexistentEmail() {
        assertThatThrownBy(() -> auth.login("nonexistent@example.com", PASSWORD, USER_AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("邮箱或密码错误");
                });
    }

    // 旧用户能登录： 用 users.insert(new UserRow(...)) 直接插入一个用户，密码哈希用 PasswordHasherTest 里的 LEGACY_ASCII，然后用 pass123 登录成功
    @Test 
    void legacyUserCanLogin() {
        users.insert(new UserRow(
                Ids.uuidV7(),
                EMAIL,
                NAME,
                LEGACY_PASSWORD_HASH,
                PASSWORD_ALGO,
                false,
                Instant.now(),
                Instant.now(),
                0L
        ));

        var result = auth.login(EMAIL, PASSWORD, USER_AGENT);

        assertThat(sessions.findActive(SessionToken.hash(result.token()), Instant.now())).isPresent();
    }

    // 退出后：findActive 查不到这个会话
    @Test
    void logoutInvalidatesSession() {
        var result = auth.register(EMAIL, NAME, PASSWORD, USER_AGENT);

        auth.logout(result.token());

        assertThat(sessions.findActive(SessionToken.hash(result.token()), Instant.now())).isNotPresent();
    }

    // 用格式错误的令牌退出：不报错
    @Test
    void logoutWithMalformedTokenDoesNotThrow() {
        auth.logout("malformed-token");
    }
}