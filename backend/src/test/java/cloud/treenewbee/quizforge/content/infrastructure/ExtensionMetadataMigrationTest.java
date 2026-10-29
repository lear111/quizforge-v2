package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import cloud.treenewbee.quizforge.MySqlTestBase;

class ExtensionMetadataMigrationTest extends MySqlTestBase {
    @Test
    void v2MetadataSurvivesV3AndBothColumnLimitsAreExpanded() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("upgrade3")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("2").load().migrate();
            String version = "v".repeat(40), name = "名".repeat(200);
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var insert = connection.prepareStatement("INSERT INTO content_extension_package VALUES (UNHEX(REPEAT('ab',32)), ?, ?, ?, ?, 1, 5, '2026-10-29 12:34:56.789')")) {
                insert.setString(1, "original-id"); insert.setString(2, version); insert.setString(3, name);
                insert.setString(4, "{\"author\":\"原作者😀\"}"); insert.executeUpdate();
            }
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                try (var rows = sql.executeQuery("SELECT HEX(digest), extension_id, version, name, manifest->>'$.author', api_major, api_min_minor, DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM content_extension_package")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("AB".repeat(32));
                    assertThat(rows.getString(2)).isEqualTo("original-id");
                    assertThat(rows.getString(3)).isEqualTo(version);
                    assertThat(rows.getString(4)).isEqualTo(name);
                    assertThat(rows.getString(5)).isEqualTo("原作者😀");
                    assertThat(rows.getInt(6)).isEqualTo(1); assertThat(rows.getInt(7)).isEqualTo(5);
                    assertThat(rows.getString(8)).isEqualTo("2026-10-29 12:34:56.789000");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("SELECT column_name, character_maximum_length FROM information_schema.columns WHERE table_schema='upgrade3' AND table_name='content_extension_package' AND column_name IN ('version','name') ORDER BY column_name")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("name"); assertThat(rows.getInt(2)).isEqualTo(300);
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("version"); assertThat(rows.getInt(2)).isEqualTo(120);
                }
            }
        }
    }
}
