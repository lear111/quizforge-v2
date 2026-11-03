package cloud.treenewbee.quizforge.practice.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PracticeCollectionMapper {
    @org.apache.ibatis.annotations.Update("""
            UPDATE practice_collection SET library_pack_id=#{pack},library_revision=#{revision}
             WHERE id=#{id} AND user_id=#{user} AND deleted_at IS NULL
            """)
    int linkLibrary(@org.apache.ibatis.annotations.Param("id") String id,
            @org.apache.ibatis.annotations.Param("user") String user,
            @org.apache.ibatis.annotations.Param("pack") String pack,
            @org.apache.ibatis.annotations.Param("revision") int revision);
    @Insert("""
            INSERT INTO practice_collection
              (id, user_id, bank_id, title, group_name, source, legacy_route_id, source_pack_id,
               source_bank_uuid, source_revision, update_status, created_at, deleted_at)
            VALUES (#{id}, #{userId}, #{bankId}, #{title}, #{groupName}, #{source}, #{legacyRouteId}, #{sourcePackId},
                    #{sourceBankUuid}, #{sourceRevision}, #{updateStatus}, #{createdAt}, #{deletedAt})
            """)
    int insert(PracticeCollectionRow row);
}
