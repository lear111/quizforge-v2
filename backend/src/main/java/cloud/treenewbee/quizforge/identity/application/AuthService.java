package cloud.treenewbee.quizforge.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.identity.domain.PasswordHasher;
import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionRow;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

@Service
public class AuthService {

    static final Duration IDLE_TIMEOUT = Duration.ofDays(14);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofDays(30);
    static final String PASSWORD_ALGO = "scrypt-v1";

    /** 登录或注册成功的结果：token 放进 Cookie，userId 用于后续逻辑。 */
    public record AuthResult(String token, String userId) {
    }

    private final UserMapper users;
    private final SessionMapper sessions;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AuthService(UserMapper users, SessionMapper sessions, PasswordHasher hasher, Clock clock,
                       TransactionTemplate transactions) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.clock = clock;
        this.transactions = transactions;
    }

    // 注册新用户，返回登录结果（token + userId）
    public AuthResult register(String email, String name, String password, String userAgent) {
        String normalizedEmail = normalizeEmail(email);
        String passwordHash = hasher.hash(password);

        return transactions.execute(status -> {
            Instant now = clock.instant();
            UserRow user = new UserRow(
                    Ids.uuidV7(),
                    normalizedEmail,
                    name,
                    passwordHash,
                    PASSWORD_ALGO,
                    false,
                    now,
                    now,
                    0L
            );
            try {
                users.insert(user);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "该邮箱已注册");
            }
            return createSession(user.id(), userAgent);
        });
    }

    // 创建新会话，返回 token + userId
    private AuthResult createSession(String userId, String userAgent) {
        String token = SessionToken.generate();
        byte[] hash = SessionToken.hash(token);
        Instant now = clock.instant();

        SessionRow session = new SessionRow(
                hash,
                userId,
                now,
                now,
                now.plus(IDLE_TIMEOUT),
                now.plus(ABSOLUTE_TIMEOUT),
                userAgent
        );

        sessions.insert(session);

        return new AuthResult(token, userId);
    }

    // 将邮箱地址规范化为小写并去除首尾空格
    static String normalizeEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请填写邮箱");
        }
        return email.toLowerCase(Locale.ROOT).trim();
    }

    // 登录：验证邮箱和密码，返回 token + userId
    public AuthResult login(String email, String password, String userAgent) {
        String normalizedEmail = normalizeEmail(email);
        Optional<UserRow> user = users.findByEmail(normalizedEmail);

        // 用户不存在时传入 null：verify 仍会完整计算一次哈希，再返回 false
        String storedHash = user.map(UserRow::passwordHash).orElse(null);
        boolean matches = hasher.verify(password, storedHash);

        if (user.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "邮箱或密码错误");
        }
        return createSession(user.get().id(), userAgent);
    }

    // 退出登录
    public void logout(String rawToken) {
        if (!SessionToken.isWellFormed(rawToken)) {
            return;
        }
        byte[] hash = SessionToken.hash(rawToken);
        sessions.deleteByHash(hash);
    }
}
