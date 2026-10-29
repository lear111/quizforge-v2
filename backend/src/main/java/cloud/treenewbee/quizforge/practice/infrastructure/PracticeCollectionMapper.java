package cloud.treenewbee.quizforge.practice.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PracticeCollectionMapper {
    @Insert("""
            INSERT INTO practice_collection
              (id, user_id, bank_id, title, group_name, source, legacy_route_id, source_pack_id,
               source_bank_key, source_revision, update_status, created_at, deleted_at)
            VALUES (#{id}, #{userId}, #{bankId}, #{title}, #{groupName}, #{source}, #{legacyRouteId}, #{sourcePackId},
                    #{sourceBankKey}, #{sourceRevision}, #{updateStatus}, #{createdAt}, #{deletedAt})
            """)
    int insert(PracticeCollectionRow row);
}
