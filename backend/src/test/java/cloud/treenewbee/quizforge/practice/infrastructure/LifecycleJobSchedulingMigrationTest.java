package cloud.treenewbee.quizforge.practice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Timeout(120)
class LifecycleJobSchedulingMigrationTest {
    static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void upgradeBackfillsSparseArchiveCursorAndClaimUsesOrderedRangeInsteadOfFilesort() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("lifecycle_upgrade")
                .withUsername("test").withPassword("test")) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            Flyway.configure().dataSource(source).target("11").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("""
                    INSERT INTO practice_collection(id,user_id,bank_id,title,source,created_at)
                    VALUES('collection','user','bank','迁移测试','{}',?)
                    """, time());
            for (int position : new int[] {4, 19, 8000}) {
                jdbc.update("""
                        INSERT INTO content_question(bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256)
                        VALUES('bank',?,?,'测试',UNHEX(REPEAT('11',32)),'{}',1,UNHEX(REPEAT('22',32)))
                        """, "q" + position, position);
                if (position != 8000) {
                    jdbc.update("""
                            INSERT INTO practice_round_question(round_id,question_id,user_id,payload,extension_digest,updated_at)
                            VALUES('round',?,'user','{}',UNHEX(REPEAT('11',32)),?)
                            """, "q" + position, time());
                }
            }
            jdbc.update("""
                    INSERT INTO practice_lifecycle_job
                        (id,user_id,collection_id,context_id,round_id,kind,status,total,completed,lease_until,created_at,updated_at)
                    VALUES('partial','user','collection','context','round','archive','running',3,2,?,?,?)
                    """, LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC), time(), time());
            // 两种数据规模均只含合成任务；增加的是终态历史，不增加可领取工作。
            addHistory(jdbc, 0, 10000);
            for (int i = 0; i < 100; i++) {
                jdbc.update("""
                        INSERT INTO practice_lifecycle_job
                            (id,user_id,collection_id,context_id,round_id,kind,status,total,created_at,updated_at)
                        VALUES(?,'user','collection','context','round','finish','queued',0,?,?)
                        """, "queued-" + i, time(), time());
            }
            jdbc.execute("ANALYZE TABLE practice_lifecycle_job");
            String oldSql = "SELECT " + LifecycleJobMapper.FIELDS.replace(",archive_position", "")
                    + " FROM practice_lifecycle_job WHERE status='queued' OR (status='running' AND lease_until < ?)"
                    + " ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED";
            var before = jdbc.queryForMap("EXPLAIN " + oldSql, time());
            output("V11，10000 条终态历史", before);

            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.queryForObject("SELECT archive_position FROM practice_lifecycle_job WHERE id='partial'", Integer.class))
                    .isEqualTo(19);
            assertThat(jdbc.queryForObject("SELECT completed FROM practice_lifecycle_job WHERE id='partial'", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_lifecycle_job WHERE status IN ('completed','failed') AND available_at IS NOT NULL", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT archive_position FROM practice_lifecycle_job WHERE id='queued-0'", Integer.class))
                    .isNull();
            String sql = String.join(" ", LifecycleJobMapper.class.getMethod("next", Instant.class)
                    .getAnnotation(Select.class).value()).replace("#{now}", "?");
            jdbc.execute("ANALYZE TABLE practice_lifecycle_job");
            var after = jdbc.queryForMap("EXPLAIN " + sql, time(), time());
            output("V13，10000 条终态历史", after);
            orderedRange(after);
            addHistory(jdbc, 10000, 20000);
            jdbc.execute("ANALYZE TABLE practice_lifecycle_job");
            var larger = jdbc.queryForMap("EXPLAIN " + sql, time(), time());
            output("V13，20000 条终态历史", larger);
            orderedRange(larger);
            assertThat(((Number) larger.get("rows")).longValue()).isLessThan(500);
            var tree = jdbc.queryForObject("EXPLAIN ANALYZE " + sql, String.class, time(), time());
            System.out.println("领取查询 V13 EXPLAIN ANALYZE：\n" + tree);
        }
    }

    private static void addHistory(JdbcTemplate jdbc, int from, int to) {
        // 常量边界来自本测试，不使用外部输入；分组写入避免逐行网络往返。
        for (int start = from; start < to; start += 1000) {
            var values = new StringBuilder();
            for (int i = start; i < start + 1000; i++) {
                if (i != start) {
                    values.append(',');
                }
                values.append("('history-").append(i)
                        .append("','user','collection','context','round','finish','")
                        .append(i % 2 == 0 ? "completed" : "failed")
                        .append("',0,'2026-10-01','2026-10-01')");
            }
            jdbc.update("INSERT INTO practice_lifecycle_job"
                    + "(id,user_id,collection_id,context_id,round_id,kind,status,total,created_at,updated_at) VALUES " + values);
        }
    }

    private static void orderedRange(Map<String, Object> explain) {
        assertThat(explain.get("key")).isEqualTo("idx_lifecycle_available");
        assertThat(explain.get("type")).isEqualTo("range");
        assertThat(String.valueOf(explain.get("Extra"))).doesNotContain("filesort");
    }

    private static void output(String label, Map<String, Object> explain) {
        System.out.println("领取查询 " + label + " EXPLAIN：" + JsonMapper.builder().build().writeValueAsString(explain));
    }

    private static LocalDateTime time() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
