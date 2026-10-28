package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import cloud.treenewbee.quizforge.MySqlTestBase;

class ContentMigrationUpgradeTest extends MySqlTestBase {
    @Test
    void v1ContentDataSurvivesUpgradeWithoutEditingTheExistingMigration() throws Exception {
        // An isolated server lets this test start at V1 without cleaning the shared Spring database.
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("upgrade").withUsername("upgrade").withPassword("upgrade-test")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("1").load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO content_blob VALUES (UNHEX(REPEAT('ab',32)), 3, 'text/plain', '2026-10-28 12:34:56.789')");
                sql.executeUpdate("INSERT INTO content_bank VALUES ('00000000-0000-0000-0000-000000000001', UNHEX(REPEAT('cd',32)), 'existing', NULL, 1, '{}', NULL, 0, '2026-10-28 12:34:56.789')");
            }
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                try (var rows = sql.executeQuery("SELECT size, mime FROM content_blob WHERE sha256=UNHEX(REPEAT('ab',32))")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(3); assertThat(rows.getString(2)).isEqualTo("text/plain");
                }
                try (var rows = sql.executeQuery("SELECT source_title FROM content_bank WHERE id='00000000-0000-0000-0000-000000000001'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("existing");
                }
                try (var rows = sql.executeQuery("SELECT status FROM admin_extension_review")) { assertThat(rows.next()).isFalse(); }
            }
        }
    }
}
