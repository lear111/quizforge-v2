package cloud.treenewbee.quizforge.catalog.infrastructure;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 练习空间懒更新读取的广场版本信息（只读 catalog 表）。 */
@Mapper
public interface PublishedVersionsMapper {

    record PackRow(String id, String status, String visibility, Integer approvedRevision) {
    }

    record BankRow(String packId, String bankUuid, String bankId) {
    }

    @Select("""
            <script>
            SELECT id, status, visibility, approved_revision FROM catalog_pack WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<PackRow> packs(@Param("ids") Collection<String> ids);

    /** 各包当前已审核版本中、指定 UUID 的题库（主键前缀 (pack_id, revision, bank_uuid) 查找）。 */
    @Select("""
            <script>
            SELECT pb.pack_id, pb.bank_uuid, pb.bank_id
              FROM catalog_pack p JOIN catalog_pack_bank pb ON pb.pack_id = p.id AND pb.revision = p.approved_revision
             WHERE p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND pb.bank_uuid IN <foreach collection="uuids" item="u" open="(" separator="," close=")">#{u}</foreach>
            </script>
            """)
    List<BankRow> banks(@Param("ids") Collection<String> ids, @Param("uuids") Collection<String> uuids);
}
