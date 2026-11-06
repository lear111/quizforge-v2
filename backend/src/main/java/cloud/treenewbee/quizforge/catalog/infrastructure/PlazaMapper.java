package cloud.treenewbee.quizforge.catalog.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 广场查询只连接已审核版本，绝不返回最新待审版本。 */
@Mapper
public interface PlazaMapper {
    /**
     * seenRevision：收藏者最后确认过的已审核版本（只在收藏列表中有值）；比当前 revision 小时显示“有新版本”与 releaseNotes。
     */
    record Entry(String id, int revision, String title, String summary, String tags, String ownerName,
                 Instant createdAt, long downloadCount, String status, Integer seenRevision, String releaseNotes) {}
    String COLUMNS = "p.id,r.revision,r.title,r.summary,r.tags,u.name AS owner_name,p.created_at,p.download_count,p.status,"
            + "NULL AS seen_revision,NULL AS release_notes";
    String FAVORITE_COLUMNS = "p.id,r.revision,r.title,r.summary,r.tags,u.name AS owner_name,p.created_at,p.download_count,p.status,"
            + "f.seen_revision,r.release_notes";
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
        INSERT INTO catalog_download_day(download_day,pack_id,downloads) VALUES(#{day},#{id},1)
        ON DUPLICATE KEY UPDATE downloads=downloads+1
        """)
    int recordDownload(@Param("day") LocalDate day, @Param("id") String id);

    @Insert("""
        INSERT IGNORE INTO catalog_favorite(user_id,pack_id,created_at,seen_revision)
        SELECT #{user},p.id,#{now},p.approved_revision FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id AND r.revision=p.approved_revision
        WHERE p.id=#{id} AND p.visibility='public' AND p.status='active' AND r.review_status='approved' AND r.validation_status='validated'
        """)
    int favorite(@Param("user") String user,@Param("id") String id,@Param("now") Instant now);

    /** 收藏者确认已看到当前已审核版本（“有新版本”提示消失）；只改自己的书签。 */
    @Update("""
        UPDATE catalog_favorite f JOIN catalog_pack p ON p.id=f.pack_id
           SET f.seen_revision=p.approved_revision
         WHERE f.user_id=#{user} AND f.pack_id=#{id} AND p.approved_revision IS NOT NULL
        """)
    int seen(@Param("user") String user,@Param("id") String id);

    @Delete("DELETE FROM catalog_favorite WHERE user_id=#{user} AND pack_id=#{id}")
    int unfavorite(@Param("user") String user,@Param("id") String id);

    @Select("SELECT COUNT(*) FROM catalog_favorite WHERE user_id=#{user} AND pack_id=#{id}")
    int isFavorite(@Param("user") String user,@Param("id") String id);

    @Select("<script>SELECT " + FAVORITE_COLUMNS + FROM + """
        JOIN catalog_favorite f ON f.pack_id=p.id
        WHERE f.user_id=#{user} AND p.visibility='public'
          <if test="after != null">AND p.id &lt; #{after}</if>
        ORDER BY p.id DESC LIMIT 21</script>
        """)
    List<Entry> favorites(@Param("user") String user,@Param("after") String after);
}
