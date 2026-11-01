package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Authorization is checked against live approval and the caller's nondeleted collections on every request. */
@Mapper
public interface ContentAccessMapper {
    String AVAILABLE = """
            c.user_id = #{userId} AND c.deleted_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM content_question blocked
                LEFT JOIN admin_extension_review r ON r.digest = blocked.extension_digest AND r.status = 'approved'
                WHERE blocked.bank_id = c.bank_id AND r.digest IS NULL)
            """;

    @Select("""
            SELECT EXISTS (SELECT 1 FROM practice_collection c
              JOIN content_question q ON q.bank_id = c.bank_id
              WHERE q.extension_digest = #{digest} AND
            """ + AVAILABLE + """
            ) OR EXISTS(SELECT 1 FROM practice_round r JOIN practice_round_question q ON q.round_id=r.id AND q.user_id=r.user_id
              JOIN practice_collection c ON c.id=r.collection_id AND c.user_id=r.user_id
              WHERE r.user_id=#{userId} AND q.extension_digest=#{digest} AND r.deleted_at IS NULL
                AND c.deleted_at IS NULL AND r.history_frozen_at IS NOT NULL)
            """)
    boolean extension(@Param("userId") String userId, @Param("digest") byte[] digest);

    @Select("""
            SELECT a.path FROM practice_collection c
              JOIN content_bank_asset a ON a.bank_id = c.bank_id
              WHERE a.blob_sha256 = #{sha256} AND
            """ + AVAILABLE + """
            UNION ALL SELECT a.path FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id
              JOIN practice_collection c ON c.id=r.collection_id AND c.user_id=r.user_id
              WHERE r.user_id=#{userId} AND a.blob_sha256=#{sha256} AND r.deleted_at IS NULL
                AND c.deleted_at IS NULL AND r.history_frozen_at IS NOT NULL LIMIT 1
            """)
    Optional<String> asset(@Param("userId") String userId, @Param("sha256") byte[] sha256);

    @Select("""
            SELECT LOWER(HEX(a.blob_sha256)) FROM practice_collection c
              JOIN content_bank_asset a ON a.bank_id = c.bank_id
              WHERE a.bank_id = #{bankId} AND a.path = #{path} AND BINARY a.path = BINARY #{path} AND
            """ + AVAILABLE + """
            UNION ALL SELECT LOWER(HEX(a.blob_sha256)) FROM practice_history_asset a JOIN practice_round r ON r.id=a.history_id
              JOIN practice_collection c ON c.id=r.collection_id AND c.user_id=r.user_id
              WHERE r.user_id=#{userId} AND JSON_UNQUOTE(JSON_EXTRACT(r.collection_snapshot,'$.sourceBankId'))=#{bankId}
                AND a.path=#{path} AND BINARY a.path=BINARY #{path} AND r.deleted_at IS NULL
                AND c.deleted_at IS NULL AND r.history_frozen_at IS NOT NULL LIMIT 1
            """)
    Optional<String> assetPath(@Param("userId") String userId, @Param("bankId") String bankId, @Param("path") String path);

    @Select("""
            SELECT DISTINCT e.manifest FROM practice_collection c
              JOIN content_question q ON q.bank_id = c.bank_id
              JOIN content_extension_package e ON e.digest = q.extension_digest WHERE
            """ + AVAILABLE + """
            UNION SELECT DISTINCT e.manifest FROM practice_round r JOIN practice_round_question q ON q.round_id=r.id AND q.user_id=r.user_id
              JOIN practice_collection c ON c.id=r.collection_id AND c.user_id=r.user_id
              JOIN content_extension_package e ON e.digest=q.extension_digest
              WHERE r.user_id=#{userId} AND r.deleted_at IS NULL AND c.deleted_at IS NULL AND r.history_frozen_at IS NOT NULL
            """)
    List<String> manifests(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM content_question WHERE bank_id = #{bankId}")
    int questionCount(@Param("bankId") String bankId);
}
