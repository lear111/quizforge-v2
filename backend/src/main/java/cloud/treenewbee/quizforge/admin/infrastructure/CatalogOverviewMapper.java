package cloud.treenewbee.quizforge.admin.infrastructure;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import cloud.treenewbee.quizforge.catalog.infrastructure.PlazaMapper;

/** 概况计数沿用广场可见性与现有审核索引；下载只汇总窗口内的日计数。 */
@Mapper
public interface CatalogOverviewMapper {
    String PUBLIC_PACKS_SQL = "SELECT COUNT(*) FROM catalog_pack p JOIN catalog_pack_revision r"
            + " ON r.pack_id=p.id AND r.revision=p.approved_revision WHERE " + PlazaMapper.VISIBLE;
    String PENDING_VERSIONS_SQL = """
            SELECT COUNT(*) FROM catalog_pack_revision r JOIN catalog_pack p ON p.id=r.pack_id
             WHERE r.review_status='pending' AND p.visibility='public' AND p.status='active'
            """;
    String PENDING_REPORTS_SQL = "SELECT COUNT(*) FROM catalog_report WHERE is_pending=1";
    String DOWNLOADS_SQL = """
            SELECT COALESCE(SUM(downloads),0) FROM catalog_download_day
             WHERE download_day >= #{from} AND download_day < #{to}
            """;

    @Select(PUBLIC_PACKS_SQL)
    long publicPacks();

    @Select(PENDING_VERSIONS_SQL)
    long pendingVersions();

    @Select(PENDING_REPORTS_SQL)
    long pendingReports();

    @Select(DOWNLOADS_SQL)
    long downloads(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
