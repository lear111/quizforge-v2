package cloud.treenewbee.quizforge.quota.infrastructure;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface QuotaMapper {
    record Account(String userId, Long storageLimit, Long dailyDownloadLimit, long storageUsed,
                   LocalDate downloadDay, long downloadUsed) {}
    record Defaults(long storageBytes, long dailyDownloadBytes) {}

    // Take an exclusive lock directly on duplicates; INSERT IGNORE's shared lock would deadlock on upgrade.
    @Insert("INSERT INTO quota_account (user_id) SELECT id FROM account_user WHERE id = #{userId} ON DUPLICATE KEY UPDATE user_id = quota_account.user_id")
    int initialize(String userId);

    @Select("SELECT user_id, storage_limit, daily_download_limit, storage_used, download_day, download_used FROM quota_account WHERE user_id = #{userId} FOR UPDATE")
    Optional<Account> lock(String userId);

    @Select("SELECT storage_bytes, daily_download_bytes FROM quota_defaults WHERE id = 1")
    Defaults defaults();

    @Select("SELECT size_bytes FROM quota_storage_item WHERE user_id = #{userId} AND category = #{category} AND object_id = #{objectId} FOR UPDATE")
    Optional<Long> item(@Param("userId") String userId, @Param("category") String category, @Param("objectId") String objectId);

    @Insert("""
            INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
            VALUES (#{userId}, #{category}, #{objectId}, #{collectionId}, #{bytes})
            ON DUPLICATE KEY UPDATE size_bytes = #{bytes}
            """)
    int putItem(@Param("userId") String userId, @Param("category") String category, @Param("objectId") String objectId,
                @Param("collectionId") String collectionId, @Param("bytes") long bytes);

    @Update("UPDATE quota_account SET storage_used = #{bytes} WHERE user_id = #{userId}")
    int storage(@Param("userId") String userId, @Param("bytes") long bytes);

    @Update("UPDATE quota_account SET download_day = #{day}, download_used = #{bytes} WHERE user_id = #{userId}")
    int download(@Param("userId") String userId, @Param("day") LocalDate day, @Param("bytes") long bytes);

    @Select("SELECT size_bytes FROM quota_storage_item WHERE user_id = #{userId} AND collection_id = #{collectionId} ORDER BY category, object_id FOR UPDATE")
    java.util.List<Long> collectionBytes(@Param("userId") String userId, @Param("collectionId") String collectionId);

    @Delete("DELETE FROM quota_storage_item WHERE user_id = #{userId} AND collection_id = #{collectionId}")
    int deleteCollection(@Param("userId") String userId, @Param("collectionId") String collectionId);

    @Select("SELECT size_bytes FROM quota_storage_item WHERE user_id = #{userId} AND category = #{category} AND LEFT(object_id, CHAR_LENGTH(#{prefix})) = #{prefix} FOR UPDATE")
    java.util.List<Long> prefixBytes(@Param("userId") String userId, @Param("category") String category, @Param("prefix") String prefix);

    @Delete("DELETE FROM quota_storage_item WHERE user_id = #{userId} AND category = #{category} AND LEFT(object_id, CHAR_LENGTH(#{prefix})) = #{prefix}")
    int deletePrefix(@Param("userId") String userId, @Param("category") String category, @Param("prefix") String prefix);

    @Update("UPDATE quota_account SET storage_limit = #{storage}, daily_download_limit = #{download} WHERE user_id = #{userId}")
    int limits(@Param("userId") String userId, @Param("storage") Long storage, @Param("download") Long download);

    @Insert("""
            INSERT INTO quota_adjustment (id, actor_id, user_id, previous_limits, new_limits, created_at)
            VALUES (#{id}, #{actor}, #{userId}, #{previous}, #{next}, #{now})
            """)
    int audit(@Param("id") String id, @Param("actor") String actor, @Param("userId") String userId,
              @Param("previous") String previous, @Param("next") String next, @Param("now") Instant now);
}
