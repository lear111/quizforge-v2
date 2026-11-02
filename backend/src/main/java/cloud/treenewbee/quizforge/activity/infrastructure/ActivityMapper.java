package cloud.treenewbee.quizforge.activity.infrastructure;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 每日活跃用户（activity_daily）。 */
@Mapper
public interface ActivityMapper {

    record DailyEntry(LocalDate day, String userId, boolean practiced) {
    }

    record DailyCount(LocalDate day, long active, long practiced) {
    }

    // 批量写入；同一天同一用户只保留一行，练习过的标记只增不减。
    @Insert("""
            <script>
            INSERT INTO activity_daily (day, user_id, practiced) VALUES
            <foreach collection="entries" item="e" separator=",">(#{e.day}, #{e.userId}, #{e.practiced})</foreach>
            ON DUPLICATE KEY UPDATE practiced = practiced OR VALUES(practiced)
            </script>
            """)
    int upsert(@Param("entries") List<DailyEntry> entries);

    @Select("""
            SELECT day, COUNT(*) AS active, COALESCE(SUM(practiced), 0) AS practiced
              FROM activity_daily WHERE day >= #{from} GROUP BY day ORDER BY day
            """)
    List<DailyCount> daily(@Param("from") LocalDate from);
}
