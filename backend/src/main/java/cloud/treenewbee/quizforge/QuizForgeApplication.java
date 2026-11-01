package cloud.treenewbee.quizforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import cloud.treenewbee.quizforge.identity.maintenance.MaintenanceCommand;

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
        // 服务器维护命令（设置管理员、登录故障恢复），见 MaintenanceCommand。
        if (MaintenanceCommand.matches(args)) {
            MaintenanceCommand.main(args);
            return;
        }
        SpringApplication.run(QuizForgeApplication.class, args);
    }
}
