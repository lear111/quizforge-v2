package cloud.treenewbee.quizforge;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Base class for integration tests: starts the full application against a real MySQL 8.4
 * container. Tests never use an in-memory database (ADR-002).
 *
 * <p>Singleton container pattern: the container is started once per JVM and never stopped by
 * JUnit. Using {@code @Testcontainers}/{@code @Container} here would stop the container after
 * each test class while Spring keeps the cached application context, so the next test class
 * would talk to a dead database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        // 缓存的测试上下文共用数据库，但各自使用不同的模拟或真实 runner。
        // 生命周期测试显式推进任务，真实调度由 Compose 浏览器验收覆盖。
        "qf.practice.jobs.enabled=false",
        // Cached test contexts share one MySQL; do not retain ten idle connections per context.
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.maximum-pool-size=4",
        // 测试不发送真实邮件或短信：验证码由 FakeVerification 记录在内存中。
        "qf.verification.mode=fake",
        // 测试从同一 IP 发出大量认证请求；限频本身由 AuthRateLimitIntegrationTest 单独覆盖。
        "qf.auth.rate-limit.requests-per-minute=100000"
})
public abstract class MySqlTestBase {

    @ServiceConnection
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("quizforge")
            .withUsername("quizforge")
            .withPassword("quizforge");

    static {
        MYSQL.start();
    }

    @Autowired
    private JdbcTemplate policyJdbc;

    // 上线默认设置要求注册验证码（V10）；集成测试统一从“全部关闭”开始，需要验证的测试自行开启。
    @BeforeEach
    void openRegistrationForTests() {
        policyJdbc.update("""
                UPDATE auth_policy SET login_code = FALSE, login_captcha = FALSE, registration_code = FALSE,
                       registration_captcha = FALSE, invitation_required = FALSE WHERE id = 1
                """);
    }
}
