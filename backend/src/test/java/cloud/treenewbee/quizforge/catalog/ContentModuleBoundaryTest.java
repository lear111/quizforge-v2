package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ContentModuleBoundaryTest {
    @Test
    void contentDoesNotImportPracticeClasses() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/cloud/treenewbee/quizforge/content"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("cloud.treenewbee.quizforge.practice.");
            }
        }
    }
}
