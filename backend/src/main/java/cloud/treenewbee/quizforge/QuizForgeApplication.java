package cloud.treenewbee.quizforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * QuizForge V2 backend entry point.
 *
 * <p>One deployable application split into business modules by package (ADR-001):
 * identity, content, catalog, practice, exam, resource, ai, admin, plus {@code shared}
 * for cross-cutting utilities without business logic.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class QuizForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizForgeApplication.class, args);
    }
}
