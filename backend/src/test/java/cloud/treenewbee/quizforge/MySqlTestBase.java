package cloud.treenewbee.quizforge;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class MySqlTestBase {

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("quizforge")
            .withUsername("quizforge")
            .withPassword("quizforge");

    static {
        MYSQL.start();
    }
}
