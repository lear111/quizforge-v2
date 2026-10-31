package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import cloud.treenewbee.quizforge.MySqlTestBase;

class CentralOutlineMigrationTest extends MySqlTestBase {
    @Test
    void v4QuestionsSurviveV5WithNoInventedGroupOrChangedContent() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("outline_upgrade")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("4").load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); var sql = connection.createStatement()) {
                sql.executeUpdate("""
                        INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,outline,data,max_score,content_sha256)
                        VALUES ('00000000-0000-4000-8000-000000000001','q1',3,'原始题目',UNHEX(REPEAT('ab',32)),
                        '{"level":"question","label":"原始标签"}','{"answer":"原值"}',1.2345,UNHEX(REPEAT('cd',32)))
                        """);
            }
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load().migrate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); var sql = connection.createStatement();
                    var rows = sql.executeQuery("SELECT position,title,outline->>'$.label',data->>'$.answer',max_score,HEX(content_sha256),outline_group_index,outline_group_name FROM content_question")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(3);
                assertThat(rows.getString(2)).isEqualTo("原始题目");assertThat(rows.getString(3)).isEqualTo("原始标签");
                assertThat(rows.getString(4)).isEqualTo("原值");assertThat(rows.getBigDecimal(5)).isEqualByComparingTo("1.2345");
                assertThat(rows.getString(6)).isEqualTo("CD".repeat(32));assertThat(rows.getObject(7)).isNull();assertThat(rows.getString(8)).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
