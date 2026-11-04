package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class MyLibraryMigrationTest {
    @Test
    void historicalPrivateInstancesBecomeHonestUnavailableOriginalsWithoutChangingUsageOrPractice() {
        try (var mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
            Flyway.configure().dataSource(source).target("23").load().migrate();
            var sql = new JdbcTemplate(source);
            sql.update("INSERT INTO account_user(id,email,name,password_hash,password_algo,is_admin,created_at,updated_at,version) VALUES('owner','legacy@example.test','用户','unused','scrypt-v1',FALSE,NOW(3),NOW(3),0)");
            sql.update("INSERT INTO content_bank(id,content_sha256,source_title,format_version,features,question_count,created_at) VALUES('bank',UNHEX(REPEAT('11',32)),'题库',2,'{}',1,NOW(3))");
            sql.update("INSERT INTO content_question(bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256) VALUES('bank','q',0,'题目',UNHEX(REPEAT('22',32)),'{}',1,UNHEX(REPEAT('33',32)))");
            for (String id : new String[]{"private-a","private-b","public","deleted"}) {
                sql.update("INSERT INTO practice_collection(id,user_id,bank_id,title,source,source_pack_id,created_at,deleted_at) VALUES(?,'owner','bank','题库','{\"type\":\"upload\",\"filename\":\"原包.qbank\"}',?,NOW(3),?)",
                        id,"public".equals(id)?"public-pack":null,"deleted".equals(id)?java.sql.Timestamp.from(java.time.Instant.now()):null);
            }
            sql.update("INSERT INTO practice_context(id,user_id,collection_id,kind,scope_key,created_at,updated_at) VALUES('ctx','owner','private-a','practice','default',NOW(3),NOW(3))");
            sql.update("INSERT INTO practice_question_state(context_id,question_id,user_id,status,answer,revision,content_sha256,updated_at) VALUES('ctx','q','owner','draft','\"原答案\"',3,UNHEX(REPEAT('33',32)),NOW(3))");
            sql.update("INSERT INTO quota_account(user_id,storage_used) VALUES('owner',333)");
            sql.update("INSERT INTO quota_storage_item VALUES('owner','import','private-a','private-a',111),('owner','import','private-b','private-b',222)");
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM catalog_pack",Integer.class)).isEqualTo(2);
            assertThat(sql.queryForList("SELECT source FROM catalog_pack_revision",String.class)).containsOnly("legacy");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM catalog_pack_revision WHERE blob_sha256 IS NULL",Integer.class)).isEqualTo(2);
            assertThat(sql.queryForList("SELECT filename FROM catalog_pack_revision",String.class)).containsOnly("原包.qbank");
            assertThat(sql.queryForList("SELECT bank_uuid FROM catalog_pack_bank",String.class)).containsOnly("bank");
            assertThat(sql.queryForList("SELECT extension_digests FROM catalog_pack_revision",String.class)).allSatisfy(value -> assertThat(value).contains("22".repeat(32)));
            assertThat(sql.queryForObject("SELECT storage_used FROM quota_account",Long.class)).isEqualTo(333);
            assertThat(sql.queryForObject("SELECT SUM(size_bytes) FROM quota_storage_item WHERE category='private_pack' AND collection_id IS NULL",Long.class)).isEqualTo(333);
            assertThat(sql.queryForObject("SELECT SUM(instance_count) FROM quota_private_revision",Long.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE library_pack_id IS NOT NULL",Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT revision FROM practice_question_state",Long.class)).isEqualTo(3);
            assertThat(sql.queryForObject("SELECT answer FROM practice_question_state",String.class)).contains("原答案");
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM catalog_pack",Integer.class)).isEqualTo(2);
        }
    }
}
