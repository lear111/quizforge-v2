package cloud.treenewbee.quizforge.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.activity.application.ActivityTracker;
import cloud.treenewbee.quizforge.admin.infrastructure.CatalogOverviewMapper;
import cloud.treenewbee.quizforge.admin.web.AdminOverviewController;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.application.PlazaService;
import cloud.treenewbee.quizforge.shared.id.Ids;

/** 合成元数据在测试事务内回滚，不依赖其他测试留下的题库。 */
@Transactional
@Import(CatalogOverviewIntegrationTest.TimeConfiguration.class)
class CatalogOverviewIntegrationTest extends MySqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogMapper catalog;
    @Autowired CatalogOverviewMapper counts;
    @Autowired AdminOverviewController overview;
    @Autowired Clock clock;
    @Autowired PlazaService plaza;
    @Autowired org.mybatis.spring.SqlSessionTemplate sessions;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        Clock overviewClock() {
            return Clock.fixed(Instant.parse("2026-11-05T16:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void downloadsUseInjectedClockAndBeijingDayInsteadOfUtcDate() {
        String id = pack("public", "active", "approved", "validated");
        plaza.download(id);
        assertThat(jdbc.queryForObject("SELECT download_day FROM catalog_download_day WHERE pack_id=?", LocalDate.class, id))
                .isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void overviewCountsOnlyVisiblePacksPublicPendingVersionsUnresolvedReportsAndQueuedImports() {
        var before = overview.overview().getBody().banks();
        String visible = pack("public", "active", "approved", "validated");
        pack("private", "active", "approved", "validated");
        pack("public", "deleted", "approved", "validated");
        pack("public", "active", "approved", "rejected");
        pack("public", "active", "pending", "queued");
        pack("private", "active", "pending", "queued");
        pack("public", "deleted", "pending", "validated");
        pack("public", "active", "rejected", "validated");
        for (String status : List.of("pending", "dismissed", "removed", "handled")) {
            jdbc.update("""
                    INSERT INTO catalog_report(id,pack_id,user_id,reason,status,created_at)
                    VALUES(?,?,?,'测试举报',?,?)
                    """, Ids.uuidV7(), visible, Ids.uuidV7(), status, clock.instant());
        }
        for (String status : List.of("queued", "running", "failed", "completed", "cancelled")) {
            jdbc.update("""
                    INSERT INTO content_import_job(id,pack_id,revision,status,available_at,created_at,updated_at)
                    VALUES(?,?,1,?,?,?,?)
                    """, Ids.uuidV7(), Ids.uuidV7(), status, clock.instant(), clock.instant(), clock.instant());
        }
        sessions.clearCache(); // JDBC 造数据不经过 MyBatis，不会自动清理事务内查询缓存。
        var after = overview.overview().getBody().banks();
        assertThat(after.publicPacks()).isEqualTo(before.publicPacks() + 1);
        assertThat(after.pendingVersions()).isEqualTo(before.pendingVersions() + 1);
        assertThat(after.pendingReports()).isEqualTo(before.pendingReports() + 1);
        assertThat(after.importQueueLength()).isEqualTo(before.importQueueLength() + 1);
        assertThat(after.downloadsLast7Days()).isEqualTo(before.downloadsLast7Days());
    }

    @Test
    void downloadWindowIncludesSevenBeijingDatesAndExcludesBothOutsideDates() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ActivityTracker.ZONE);
        LocalDate first = today.minusDays(6);
        long before = counts.downloads(first, today.plusDays(1));
        for (var entry : List.of(first.minusDays(1), first, today, today.plusDays(1))) {
            jdbc.update("INSERT INTO catalog_download_day(download_day,pack_id,downloads) VALUES(?,?,3)",
                    entry, Ids.uuidV7());
        }
        sessions.clearCache();
        assertThat(counts.downloads(first, today.plusDays(1))).isEqualTo(before + 6);
        assertThat(overview.overview().getBody().banks().downloadsLast7Days()).isEqualTo(before + 6);
        assertThat(counts.downloads(LocalDate.of(3000, 1, 1), LocalDate.of(3000, 1, 2))).isZero();
    }

    @Test
    void overviewQueriesUseIndexesInsteadOfFullTableScans() {
        pack("public", "active", "approved", "validated");
        pack("public", "active", "pending", "queued");
        for (String query : List.of(CatalogOverviewMapper.PUBLIC_PACKS_SQL,
                CatalogOverviewMapper.PENDING_VERSIONS_SQL, CatalogOverviewMapper.PENDING_REPORTS_SQL,
                "SELECT COALESCE(SUM(status='queued'),0) FROM content_import_job WHERE status IN ('queued','running','failed')")) {
            explain(query);
        }
        // 有窗口外数据，确认时间窗口通过日期主键取范围。
        for (int day = 1; day <= 28; day++) {
            jdbc.update("INSERT INTO catalog_download_day(download_day,pack_id,downloads) VALUES(?,?,1)",
                    LocalDate.of(3000, 1, day), Ids.uuidV7());
        }
        explain(CatalogOverviewMapper.DOWNLOADS_SQL.replace("#{from}", "'3000-01-10'").replace("#{to}", "'3000-01-17'"));
    }

    private void explain(String query) {
        var plan = jdbc.queryForList("EXPLAIN " + query);
        System.out.println("概况查询执行计划：" + query + " -> " + plan);
        assertThat(plan).allSatisfy(row -> {
            assertThat(row.get("type")).isNotEqualTo("ALL");
            if (row.get("table") != null) assertThat(row.get("key")).isNotNull();
        });
    }

    private String pack(String visibility, String status, String review, String validation) {
        String id = Ids.uuidV7();
        String user = Ids.uuidV7();
        catalog.insertPack(id, user, clock.instant());
        catalog.insertRevision(new CatalogMapper.RevisionRow(id, 1, "统计样例", "简介", "说明", "其他", "[]", "",
                "upload", "统计.qbank", new byte[32], 1, "[]", review, null, null, null, null, user, clock.instant()));
        jdbc.update("UPDATE catalog_pack SET visibility=?,status=?,approved_revision=1 WHERE id=?", visibility, status, id);
        jdbc.update("UPDATE catalog_pack_revision SET review_status=?,validation_status=? WHERE pack_id=?", review, validation, id);
        return id;
    }
}
