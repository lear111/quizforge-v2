package cloud.treenewbee.quizforge.library.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 用户资源目录；列表和原文件查询在 SQL 中限定所有者。 */
@Mapper
public interface MyLibraryMapper {
    record Pack(String id, String visibility, int latestRevision, String title, Instant createdAt) {}
    record Original(String filename, byte[] blobSha256, long size) {}
    record Bank(String bankUuid, String bankId, String title, int questionCount) {}

    @Select("""
            <script>
            SELECT p.id,p.visibility,p.latest_revision,r.title,p.created_at
              FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id AND r.revision=p.latest_revision
             WHERE p.owner_id=#{user} AND p.status='active'
               <if test="after != null">AND p.id &lt; #{after}</if>
             ORDER BY p.id DESC LIMIT 50
            </script>
            """)
    List<Pack> mine(@Param("user") String user, @Param("after") String after);

    @Select("""
            SELECT r.filename,r.blob_sha256,r.size FROM catalog_pack p
              JOIN catalog_pack_revision r ON r.pack_id=p.id
             WHERE p.id=#{pack} AND p.owner_id=#{user} AND p.status='active' AND r.revision=#{revision}
            """)
    Optional<Original> original(@Param("user") String user, @Param("pack") String pack, @Param("revision") int revision);

    @Select("""
            SELECT pb.bank_uuid,pb.bank_id,pb.title,b.question_count FROM catalog_pack_bank pb
              JOIN content_bank b ON b.id=pb.bank_id
             WHERE pb.pack_id=#{pack} AND pb.revision=#{revision} ORDER BY pb.position,pb.bank_uuid
            """)
    List<Bank> banks(@Param("pack") String pack, @Param("revision") int revision);
}
