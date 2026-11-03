package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 上传附加元数据；用户可见查询均带归属条件。 */
@Mapper
public interface ImportCatalogMapper {
    record State(String packId, int revision, String visibility, String validationStatus, String validationReason) {}

    @Select("""
            SELECT p.id AS pack_id,r.revision,p.visibility,r.validation_status,r.validation_reason
              FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id
             WHERE p.id=#{pack} AND p.owner_id=#{user} AND p.status='active' AND r.revision=#{revision}
            """)
    Optional<State> owned(@Param("pack") String pack, @Param("user") String user, @Param("revision") int revision);

    @Select("""
            SELECT p.id AS pack_id,r.revision,p.visibility,r.validation_status,r.validation_reason
              FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id AND r.revision=p.latest_revision
             WHERE p.id=#{pack} AND p.owner_id=#{user} AND p.status='active'
            """)
    Optional<State> latestOwned(@Param("pack") String pack, @Param("user") String user);

    @Update("UPDATE catalog_pack SET visibility=#{visibility} WHERE id=#{pack} AND owner_id=#{user}")
    int visibility(@Param("pack") String pack, @Param("user") String user, @Param("visibility") String visibility);

    @Update("""
            UPDATE catalog_pack_revision SET validation_status=#{status},validation_reason=#{reason}
             WHERE pack_id=#{pack} AND revision=#{revision}
            """)
    int validation(@Param("pack") String pack, @Param("revision") int revision,
            @Param("status") String status, @Param("reason") String reason);

    @Update("UPDATE catalog_pack_revision SET extension_digests=#{digests} WHERE pack_id=#{pack} AND revision=#{revision}")
    int digests(@Param("pack") String pack, @Param("revision") int revision, @Param("digests") String digests);

    @Select("""
            <script>
            SELECT p.id AS pack_id,r.revision,p.visibility,r.validation_status,r.validation_reason
              FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id
             WHERE p.status='active' AND r.review_status='pending' AND r.validation_status='validated'
               AND p.visibility='private'
               <if test="after != null">AND (p.id > #{after} OR (p.id=#{after} AND r.revision > #{revision}))</if>
             ORDER BY p.id,r.revision LIMIT 100
            </script>
            """)
    List<State> waitingPrivate(@Param("after") String after, @Param("revision") int revision);

    @Update("""
            UPDATE catalog_pack_revision SET notified_status=#{status}
             WHERE pack_id=#{pack} AND revision=#{revision} AND (notified_status IS NULL OR notified_status != #{status})
            """)
    int notified(@Param("pack") String pack, @Param("revision") int revision, @Param("status") String status);

    @Select("SELECT validation_status FROM catalog_pack_revision WHERE pack_id=#{pack} AND revision=#{revision}")
    String validationStatus(@Param("pack") String pack, @Param("revision") int revision);

    @Update("""
            UPDATE catalog_pack_revision r JOIN content_import_job j ON j.pack_id=r.pack_id AND j.revision=r.revision
               SET r.validation_status='processing_failed',r.validation_reason=NULL
             WHERE j.status='failed' AND r.validation_status IN ('queued','processing')
            """)
    int failedJobs();
}
