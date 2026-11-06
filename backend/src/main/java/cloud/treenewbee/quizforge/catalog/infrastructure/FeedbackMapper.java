package cloud.treenewbee.quizforge.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 评论按包分页，举报按未处理优先分页；写入始终带归属或状态条件。 */
@Mapper
public interface FeedbackMapper {
    record Comment(String id, String packId, String userId, String author, String body, Instant createdAt) {}
    record Report(String id, String packId, String userId, String reporter, String reason, String status,
                  String resolution, String reviewerId, String reviewer, Instant reviewedAt, Instant createdAt,
                  int isPending, String title, String packStatus) {}

    String COMMENTS = "c.id,c.pack_id,c.user_id,u.name AS author,c.body,c.created_at";
    String REPORTS = "r.id,r.pack_id,r.user_id,u.name AS reporter,r.reason,r.status,r.resolution,r.reviewer_id,"
            + "a.name AS reviewer,r.reviewed_at,r.created_at,r.is_pending,v.title,p.status AS pack_status";
    String REPORT_FROM = " FROM catalog_report r JOIN catalog_pack p ON p.id=r.pack_id "
            + "LEFT JOIN catalog_pack_revision v ON v.pack_id=p.id AND v.revision=p.approved_revision "
            + "LEFT JOIN account_user u ON u.id=r.user_id LEFT JOIN account_user a ON a.id=r.reviewer_id ";

    @Select("<script>SELECT " + COMMENTS + " FROM catalog_comment c LEFT JOIN account_user u ON u.id=c.user_id WHERE c.pack_id=#{pack} " + """
            <if test="after != null">AND (c.created_at &lt; #{time} OR (c.created_at=#{time} AND c.id &lt; #{after}))</if>
            ORDER BY c.created_at DESC,c.id DESC LIMIT 21</script>
            """)
    List<Comment> comments(@Param("pack") String pack, @Param("time") Instant time, @Param("after") String after);

    @Insert("INSERT INTO catalog_comment(id,pack_id,user_id,body,created_at) VALUES(#{id},#{pack},#{user},#{body},#{now})")
    int insertComment(@Param("id") String id, @Param("pack") String pack, @Param("user") String user,
                      @Param("body") String body, @Param("now") Instant now);

    @Select("SELECT " + COMMENTS + " FROM catalog_comment c LEFT JOIN account_user u ON u.id=c.user_id WHERE c.id=#{id}")
    Optional<Comment> comment(@Param("id") String id);

    @Delete("DELETE FROM catalog_comment WHERE id=#{id} AND (user_id=#{user} OR #{admin}=TRUE)")
    int deleteComment(@Param("id") String id, @Param("user") String user, @Param("admin") boolean admin);

    @Insert("""
            INSERT INTO catalog_report(id,pack_id,user_id,reason,created_at) VALUES(#{id},#{pack},#{user},#{reason},#{now})
            ON DUPLICATE KEY UPDATE id=catalog_report.id
            """)
    int insertReport(@Param("id") String id, @Param("pack") String pack, @Param("user") String user,
                     @Param("reason") String reason, @Param("now") Instant now);

    @Select("SELECT id FROM catalog_report WHERE pack_id=#{pack} AND pending_user=#{user} FOR UPDATE")
    Optional<String> pending(@Param("pack") String pack, @Param("user") String user);

    @Select("<script>SELECT " + REPORTS + REPORT_FROM + " WHERE 1=1 " + """
            <if test="after != null">AND (r.is_pending &lt; #{pending} OR (r.is_pending=#{pending} AND
              (r.created_at &lt; #{time} OR (r.created_at=#{time} AND r.id &lt; #{after}))))</if>
            ORDER BY r.is_pending DESC,r.created_at DESC,r.id DESC LIMIT 21</script>
            """)
    List<Report> reports(@Param("pending") Integer pending, @Param("time") Instant time, @Param("after") String after);

    @Select("SELECT " + REPORTS + REPORT_FROM + " WHERE r.id=#{id}")
    Optional<Report> report(@Param("id") String id);

    @Select("SELECT id FROM catalog_report WHERE id=#{id} FOR UPDATE")
    Optional<String> lockReport(@Param("id") String id);

    @Update("""
            UPDATE catalog_report SET status=#{decision},resolution=#{note},reviewer_id=#{admin},reviewed_at=#{now}
            WHERE id=#{id} AND status='pending'
            """)
    int resolve(@Param("id") String id, @Param("decision") String decision, @Param("note") String note,
                @Param("admin") String admin, @Param("now") Instant now);

    @Insert("""
            INSERT INTO catalog_feedback_rate(user_id,kind,window_until,attempts) VALUES(#{user},#{kind},#{until},1)
            ON DUPLICATE KEY UPDATE attempts=IF(window_until <= #{now},1,attempts+1),
              window_until=IF(window_until <= #{now},#{until},window_until)
            """)
    int hit(@Param("user") String user, @Param("kind") String kind, @Param("now") Instant now, @Param("until") Instant until);

    @Select("SELECT attempts FROM catalog_feedback_rate WHERE user_id=#{user} AND kind=#{kind}")
    int attempts(@Param("user") String user, @Param("kind") String kind);
}
