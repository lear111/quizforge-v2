package cloud.treenewbee.quizforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that the Flyway migrations from the database design run on real MySQL
 * (closes open item DB-8 for the core tables).
 */
class SchemaMigrationTest extends MySqlTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void coreTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables).contains(
                "account_user", "auth_session", "auth_device",
                "content_bank", "content_question", "content_extension_package",
                "practice_collection", "practice_context", "practice_question_state",
                "practice_round", "practice_request_receipt",
                "exam_exam");
    }

    @Test
    void tablesUseUtf8mb4() {
        List<String> nonUtf8 = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name <> 'flyway_schema_history'
                   AND table_collation <> 'utf8mb4_0900_ai_ci'
                """, String.class);

        assertThat(nonUtf8).isEmpty();
    }
}
