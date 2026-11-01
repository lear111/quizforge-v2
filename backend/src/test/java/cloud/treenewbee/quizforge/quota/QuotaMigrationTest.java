package cloud.treenewbee.quizforge.quota;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class QuotaMigrationTest {
    @Test void existingPracticeChargesAreReleasedWithoutChangingPrivateImportsOrUserData() {
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("quota_legacy").withUsername("test").withPassword("test")) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
            Flyway.configure().dataSource(source).target("5").load().migrate();
            var sql = new JdbcTemplate(source);
            sql.update("INSERT INTO account_user VALUES ('user','u@example.test','legacy','unused','scrypt-v1',FALSE,NOW(3),NOW(3),0)");
            sql.update("INSERT INTO content_bank VALUES ('bank',UNHEX(REPEAT('11',32)),'legacy',NULL,2,'{}',NULL,1,NOW(3))");
            sql.update("INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256) VALUES ('bank','q1',0,'test',UNHEX(REPEAT('22',32)),'{}',1,UNHEX(REPEAT('33',32)))");
            sql.update("INSERT INTO content_blob VALUES (UNHEX(REPEAT('44',32)),100,'image/png',NOW(3))");
            sql.update("INSERT INTO content_bank_asset VALUES ('bank','assets/a.png',UNHEX(REPEAT('44',32)))");
            sql.update("INSERT INTO content_extension_file VALUES (UNHEX(REPEAT('22',32)),'rules.js',UNHEX(REPEAT('44',32)))");
            for (String id : new String[]{"private-a","private-b","public","deleted"}) {
                sql.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,source_pack_id,created_at,deleted_at) VALUES (?,'user','bank','legacy',?,?,NOW(3),?)",id,
                        id.equals("public")?"{\"type\":\"public\"}":"{\"type\":\"upload\"}",id.equals("public")?"pack":null,id.equals("deleted")?java.sql.Timestamp.from(java.time.Instant.now()):null);
            }
            sql.update("INSERT INTO practice_context (id,user_id,collection_id,kind,scope_key,created_at,updated_at) VALUES ('context','user','private-a','practice','default',NOW(3),NOW(3))");
            sql.update("INSERT INTO practice_question_state (context_id,question_id,user_id,status,answer,revision,content_sha256,updated_at) VALUES ('context','q1','user','draft','\"中\"',1,UNHEX(REPEAT('33',32)),NOW(3))");
            sql.update("INSERT INTO practice_whiteboard VALUES ('context','q1','user','{}',2,NOW(3))");
            sql.update("INSERT INTO practice_round (id,user_id,collection_id,context_id,mode,status,collection_snapshot,summary,created_at,updated_at) VALUES ('round','user','private-a','context','practice','completed','{}','{}',NOW(3),NOW(3))");
            sql.update("INSERT INTO practice_round_question VALUES ('round','q1','user','{}',UNHEX(REPEAT('22',32)),NOW(3))");
            sql.update("INSERT INTO practice_user_resource VALUES ('user',UNHEX(REPEAT('44',32)),'image/png',100,NOW(3))");
            Flyway.configure().dataSource(source).target("6").load().migrate();
            long previous = sql.queryForObject("SELECT storage_used FROM quota_account WHERE user_id='user'",Long.class);
            assertThat(previous).isPositive();
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE category='import'",Long.class)).isEqualTo(2);
            var sizes = sql.queryForList("SELECT size_bytes FROM quota_storage_item WHERE category='import'",Long.class);
            assertThat(sizes.get(0)).isEqualTo(sizes.get(1)).isGreaterThan(200);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE category<>'import'",Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT storage_used FROM quota_account WHERE user_id='user'",Long.class)).isEqualTo(sizes.get(0)*2).isLessThan(previous);
            assertThat(sql.queryForObject("SELECT storage_limit FROM quota_account WHERE user_id='user'",Long.class)).isNull();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM practice_collection",Long.class)).isEqualTo(4);
            assertThat(sql.queryForObject("SELECT answer FROM practice_question_state",String.class)).isEqualTo("\"中\"");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM practice_user_resource",Long.class)).isEqualTo(1);
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(sql.queryForObject("SELECT storage_used FROM quota_account WHERE user_id='user'",Long.class)).isEqualTo(sizes.get(0)*2);
        }
    }
}
