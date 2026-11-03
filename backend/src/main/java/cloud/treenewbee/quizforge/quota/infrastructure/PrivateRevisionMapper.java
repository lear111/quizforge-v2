package cloud.treenewbee.quizforge.quota.infrastructure;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 私人版本引用计数；调用方必须先锁定用户配额行。 */
@Mapper
public interface PrivateRevisionMapper {
    record Reference(String packId, int revision, boolean resourcePresent, long instanceCount) {}
    record Source(String packId, int revision) {}

    @Insert("""
            INSERT INTO quota_private_revision(user_id,pack_id,revision,resource_present,instance_count)
            VALUES(#{user},#{pack},#{revision},TRUE,0)
            """)
    int insert(@Param("user") String user, @Param("pack") String pack, @Param("revision") int revision);

    @Update("""
            UPDATE quota_private_revision SET instance_count=instance_count+#{delta}
             WHERE user_id=#{user} AND pack_id=#{pack} AND revision=#{revision} AND instance_count+#{delta} >= 0
            """)
    int instances(@Param("user") String user, @Param("pack") String pack, @Param("revision") int revision,
            @Param("delta") int delta);

    @Update("UPDATE quota_private_revision SET resource_present=FALSE WHERE user_id=#{user} AND pack_id=#{pack}")
    int removeResource(@Param("user") String user, @Param("pack") String pack);

    @Select("SELECT pack_id,revision,resource_present,instance_count FROM quota_private_revision WHERE user_id=#{user} AND pack_id=#{pack} ORDER BY revision FOR UPDATE")
    List<Reference> references(@Param("user") String user, @Param("pack") String pack);

    @Select("""
            SELECT c.library_pack_id AS pack_id,c.library_revision AS revision FROM practice_collection c
              JOIN quota_private_revision r ON r.user_id=c.user_id AND r.pack_id=c.library_pack_id AND r.revision=c.library_revision
             WHERE c.id=#{collection} AND c.user_id=#{user}
            """)
    Optional<Source> source(@Param("user") String user, @Param("collection") String collection);

    @Delete("DELETE FROM quota_private_revision WHERE user_id=#{user} AND pack_id=#{pack} AND revision=#{revision} AND resource_present=FALSE AND instance_count=0")
    int removeUnused(@Param("user") String user, @Param("pack") String pack, @Param("revision") int revision);
}
