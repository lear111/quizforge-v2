package cloud.treenewbee.quizforge.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 广场查询只连接已审核版本，绝不返回最新待审版本。 */
@Mapper
public interface PlazaMapper {
    record Entry(String id, int revision, String title, String summary, String tags, String ownerName,
                 Instant createdAt, long downloadCount, String status) {}
    String COLUMNS = "p.id,r.revision,r.title,r.summary,r.tags,u.name AS owner_name,p.created_at,p.download_count,p.status";
    String FROM = " FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id AND r.revision=p.approved_revision LEFT JOIN account_user u ON u.id=p.owner_id ";
    String VISIBLE = "p.visibility='public' AND p.status='active' AND r.review_status='approved' AND r.validation_status='validated'";

    @Select("<script>SELECT " + COLUMNS + FROM + " WHERE " + VISIBLE + """
        <if test="query != ''">AND (LOCATE(#{query},r.title)>0 OR LOCATE(#{query},r.summary)>0 OR LOCATE(#{query},CAST(r.tags AS CHAR))>0)</if>
        <if test="after != null">
          <choose><when test="popular">AND (p.download_count &lt; #{count} OR (p.download_count=#{count} AND p.id &lt; #{after}))</when>
          <otherwise>AND (p.created_at &lt; #{time} OR (p.created_at=#{time} AND p.id &lt; #{after}))</otherwise></choose>
        </if>
        ORDER BY <choose><when test="popular">p.download_count</when><otherwise>p.created_at</otherwise></choose> DESC,p.id DESC LIMIT 21
        </script>
        """)
    List<Entry> list(@Param("query") String query, @Param("popular") boolean popular,
        @Param("after") String after, @Param("count") long count, @Param("time") Instant time);

    @Select("SELECT " + COLUMNS + FROM + " WHERE " + VISIBLE + " AND p.id=#{id}")
    Optional<Entry> visible(@Param("id") String id);

    @Update("""
        UPDATE catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id AND r.revision=p.approved_revision
        SET p.download_count=p.download_count+1
        WHERE p.id=#{id} AND p.approved_revision=#{revision} AND p.visibility='public' AND p.status='active'
          AND r.review_status='approved' AND r.validation_status='validated'
        """)
    int download(@Param("id") String id,@Param("revision") int revision);

    @Insert("""
        INSERT IGNORE INTO catalog_favorite(user_id,pack_id,created_at)
        SELECT #{user},p.id,#{now} FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id AND r.revision=p.approved_revision
        WHERE p.id=#{id} AND p.visibility='public' AND p.status='active' AND r.review_status='approved' AND r.validation_status='validated'
        """)
    int favorite(@Param("user") String user,@Param("id") String id,@Param("now") Instant now);

    @Delete("DELETE FROM catalog_favorite WHERE user_id=#{user} AND pack_id=#{id}")
    int unfavorite(@Param("user") String user,@Param("id") String id);

    @Select("SELECT COUNT(*) FROM catalog_favorite WHERE user_id=#{user} AND pack_id=#{id}")
    int isFavorite(@Param("user") String user,@Param("id") String id);

    @Select("<script>SELECT " + COLUMNS + FROM + """
        JOIN catalog_favorite f ON f.pack_id=p.id
        WHERE f.user_id=#{user} AND p.visibility='public'
          <if test="after != null">AND p.id &lt; #{after}</if>
        ORDER BY p.id DESC LIMIT 21</script>
        """)
    List<Entry> favorites(@Param("user") String user,@Param("after") String after);
}
