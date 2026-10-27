package cloud.treenewbee.quizforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * QuizForge V2 backend entry point.
 *
 * <p>One deployable application split into business modules by package (ADR-001):
 * identity, content, catalog, practice, exam, resource, ai, admin, plus {@code shared}
 * for cross-cutting utilities without business logic.
 */
@SpringBootApplication
public class QuizForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizForgeApplication.class, args);
    }
}
