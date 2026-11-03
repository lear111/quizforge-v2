package cloud.treenewbee.quizforge.admin.infrastructure;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ImportOverviewMapper {
    record Uploader(String userId, String name, long uploads) {}
    record Counts(long queued, long running, long failed) {}

    @Select("SELECT COALESCE(SUM(uploads),0) FROM content_upload_day WHERE upload_day=#{day}")
    long uploads(@Param("day") LocalDate day);

    @Select("""
            SELECT d.user_id,u.name,d.uploads FROM content_upload_day d JOIN account_user u ON u.id=d.user_id
             WHERE d.upload_day=#{day} ORDER BY d.uploads DESC,d.user_id LIMIT 5
            """)
    List<Uploader> top(@Param("day") LocalDate day);

    @Select("""
            SELECT COALESCE(SUM(status='queued'),0) AS queued,COALESCE(SUM(status='running'),0) AS running,
                   COALESCE(SUM(status='failed'),0) AS failed
              FROM content_import_job WHERE status IN ('queued','running','failed')
            """)
    Counts queue();
}
