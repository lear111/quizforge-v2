package cloud.treenewbee.quizforge.practice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class PracticeCompletionMigrationTest {
    @Test void upgradePreservesOldAttemptsAndDistinguishesCurrentRoundFromFrozenHistory() {
        try (var mysql=new MySQLContainer("mysql:8.4").withDatabaseName("practice_upgrade").withUsername("test").withPassword("test")) {
            mysql.start();
            var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
            Flyway.configure().dataSource(source).target("9").load().migrate();
            var jdbc=new JdbcTemplate(source);
            jdbc.update("INSERT INTO practice_context(id,user_id,collection_id,kind,scope_key,current_round_id,created_at,updated_at) VALUES('context','user','collection','practice','default','current',NOW(3),NOW(3))");
            for(String id:new String[]{"current","past"})jdbc.update("INSERT INTO practice_round(id,user_id,collection_id,context_id,mode,status,collection_snapshot,summary,created_at,updated_at,completed_at) VALUES(?,'user','collection','context','practice','completed','{}','{}',NOW(3),NOW(3),NOW(3))",id);
            for(int generation:new int[]{0,1,4})jdbc.update("""
                INSERT INTO practice_question_state(context_id,question_id,user_id,status,answer,grading_status,score,max_score,revision,submission_generation,grading_generation,content_sha256,updated_at)
                VALUES('context',?,'user',?,'{}','graded',1,1,1,?,?,UNHEX(REPEAT('11',32)),NOW(3))
                ""","q"+generation,generation==0?"draft":"submitted",generation,generation==4?2:0);
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.queryForObject("SELECT completion_status FROM practice_context",String.class)).isEqualTo("completed");
            assertThat(jdbc.queryForObject("SELECT history_frozen_at IS NULL FROM practice_round WHERE id='current'",Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT history_frozen_at IS NOT NULL FROM practice_round WHERE id='past'",Boolean.class)).isTrue();
            assertThat(jdbc.queryForMap("SELECT attempt_count,first_correct,grading_source FROM practice_question_state WHERE question_id='q0'"))
                    .containsEntry("attempt_count",0).containsEntry("first_correct",null).containsEntry("grading_source",null);
            assertThat(jdbc.queryForMap("SELECT attempt_count,first_correct,grading_source FROM practice_question_state WHERE question_id='q1'"))
                    .containsEntry("attempt_count",1).containsEntry("first_correct",true).containsEntry("grading_source","rule");
            assertThat(jdbc.queryForMap("SELECT attempt_count,first_correct,grading_source FROM practice_question_state WHERE question_id='q4'"))
                    .containsEntry("attempt_count",null).containsEntry("first_correct",null).containsEntry("grading_source",null);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE answer=CAST('{}' AS JSON)",Integer.class)).isEqualTo(3);
        }
    }
}
