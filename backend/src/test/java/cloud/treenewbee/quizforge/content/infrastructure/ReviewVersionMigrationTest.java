package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import cloud.treenewbee.quizforge.MySqlTestBase;

class ReviewVersionMigrationTest extends MySqlTestBase {
    @Test
    void v3ReviewsSurviveV4AndAcceptOneHundredTwentyCharacterVersions() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("review_upgrade")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("3").load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO admin_extension_review VALUES (UNHEX(REPEAT('ab',32)), 'original', REPEAT('v',40), 'approved', 'reviewer', '2026-10-29 12:34:56.789', '原始理由😀')");
            }
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                try (var rows = sql.executeQuery("SELECT HEX(digest),extension_id,version,status,reviewer,DATE_FORMAT(reviewed_at,'%Y-%m-%d %H:%i:%s.%f'),reason FROM admin_extension_review")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("AB".repeat(32));
                    assertThat(rows.getString(2)).isEqualTo("original"); assertThat(rows.getString(3)).isEqualTo("v".repeat(40));
                    assertThat(rows.getString(4)).isEqualTo("approved"); assertThat(rows.getString(5)).isEqualTo("reviewer");
                    assertThat(rows.getString(6)).isEqualTo("2026-10-29 12:34:56.789000"); assertThat(rows.getString(7)).isEqualTo("原始理由😀");
                }
                assertThat(sql.executeUpdate("INSERT INTO admin_extension_review (digest,extension_id,version) VALUES (UNHEX(REPEAT('cd',32)), 'long', REPEAT('v',120))")).isEqualTo(1);
                try(var rows = sql.executeQuery("SELECT version FROM admin_extension_review WHERE extension_id='long'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).hasSize(120);
                }
            }
        }
    }
}
