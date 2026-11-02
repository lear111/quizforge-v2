package cloud.treenewbee.quizforge.library;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LibraryMapper {
    record Item(String id, String bankId, String title, String groupName) {}

    @Insert("INSERT INTO library_catalog (user_id) VALUES (#{userId}) ON DUPLICATE KEY UPDATE revision = revision")
    int initialize(@Param("userId") String userId);

    @Select("SELECT revision FROM library_catalog WHERE user_id = #{userId} FOR UPDATE")
    long lock(@Param("userId") String userId);

    @Select("SELECT COALESCE((SELECT revision FROM library_catalog WHERE user_id = #{userId}), 0)")
    long revision(@Param("userId") String userId);

    @Update("UPDATE library_catalog SET revision = revision + 1 WHERE user_id = #{userId}")
    int bump(@Param("userId") String userId);

    @Select("SELECT name FROM library_group WHERE user_id = #{userId} ORDER BY name")
    List<String> groups(@Param("userId") String userId);

    @Insert("INSERT IGNORE INTO library_group (user_id, name) VALUES (#{userId}, #{name})")
    int addGroup(@Param("userId") String userId, @Param("name") String name);

    @Delete("DELETE FROM library_group WHERE user_id = #{userId} AND name = #{name}")
    int removeGroup(@Param("userId") String userId, @Param("name") String name);

    @Select("""
            SELECT id, bank_id, title, group_name FROM practice_collection
            WHERE user_id = #{userId} AND deleted_at IS NULL ORDER BY id
            """)
    List<Item> items(@Param("userId") String userId);

    @Select("""
            SELECT id, bank_id, title, group_name FROM practice_collection
            WHERE user_id = #{userId} AND id = #{id} AND deleted_at IS NULL
            """)
    Optional<Item> item(@Param("userId") String userId, @Param("id") String id);

    @Select("""
            SELECT COUNT(*) FROM practice_collection
            WHERE user_id = #{userId} AND deleted_at IS NULL AND id != #{exceptId}
              AND BINARY group_name <=> BINARY #{group}
              AND title COLLATE utf8mb4_0900_as_ci = #{title} COLLATE utf8mb4_0900_as_ci
            """)
    int duplicate(@Param("userId") String userId, @Param("exceptId") String exceptId,
            @Param("group") String group, @Param("title") String title);

    @Update("""
            UPDATE practice_collection SET title = #{title}, group_name = #{group}
            WHERE user_id = #{userId} AND id = #{id} AND deleted_at IS NULL
            """)
    int update(@Param("userId") String userId, @Param("id") String id,
            @Param("title") String title, @Param("group") String group);

    @Insert("INSERT IGNORE INTO content_bank_document (bank_id, author_json, author_bindings) VALUES (#{id}, #{document}, #{bindings})")
    int preserve(@Param("id") String id, @Param("document") String document, @Param("bindings") String bindings);

    @Select("SELECT CAST(author_json AS CHAR CHARACTER SET utf8mb4) FROM content_bank_document WHERE bank_id = #{id}")
    String author(@Param("id") String id);
}
