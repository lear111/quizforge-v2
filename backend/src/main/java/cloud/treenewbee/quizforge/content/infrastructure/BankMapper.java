package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BankMapper {
    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM content_bank b WHERE b.id = #{bankId} AND NOT EXISTS (
                SELECT 1 FROM content_question q LEFT JOIN admin_extension_review r
                  ON r.digest = q.extension_digest AND r.status = 'approved'
                 WHERE q.bank_id = b.id AND r.digest IS NULL
              )
            )
            """)
    boolean isAvailable(@Param("bankId") String bankId);

    /** 题库所用拓展中最差的审核状态及未通过的原因（设计 04 第 6.2 节）；在读取时由白名单推算。 */
    record ReviewState(String status, String reason) {}

    @Select("""
            SELECT CASE WHEN SUM(r.status = 'rejected') > 0 THEN 'rejected'
                        WHEN SUM(r.status = 'revoked') > 0 THEN 'revoked'
                        WHEN SUM(r.digest IS NULL OR r.status = 'pending') > 0 THEN 'pending'
                        ELSE 'approved' END AS status,
                   MAX(CASE WHEN r.status = 'rejected' THEN r.reason END) AS reason
              FROM (SELECT DISTINCT extension_digest FROM content_question WHERE bank_id = #{bankId}) q
              LEFT JOIN admin_extension_review r ON r.digest = q.extension_digest
            """)
    ReviewState reviewState(@Param("bankId") String bankId);

    @Select("""
            SELECT id FROM content_bank WHERE content_sha256 = #{contentSha256} FOR SHARE
            """)
    Optional<String> findIdByContentSha256ForShare(@Param("contentSha256") byte[] contentSha256);

    /** V21 之前导入的内容没有 UUID：再次导入相同内容时补上（UUID 参与内容哈希，内容相同则 UUID 相同）。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE content_bank SET bank_uuid = #{bankUuid} WHERE id = #{id} AND bank_uuid IS NULL
            """)
    int fillIdentity(@Param("id") String id, @Param("bankUuid") String bankUuid);

    @Insert("""
            INSERT INTO content_bank (id, content_sha256, bank_uuid, source_title, description, format_version, features, listening, question_count, created_at)
            VALUES (#{id}, #{contentSha256}, #{bankUuid}, #{sourceTitle}, #{description}, #{formatVersion}, #{features}, #{listening}, #{questionCount}, #{createdAt})
            """)
    int insert(BankRow row);

    @Select("""
            SELECT id, content_sha256, bank_uuid, source_title, description, format_version, features, listening, question_count, created_at
              FROM content_bank WHERE id = #{id}
            """)
    Optional<BankRow> findById(@Param("id") String id);

    @Select("""
            SELECT id, content_sha256, bank_uuid, source_title, description, format_version, features, listening, question_count, created_at
              FROM content_bank WHERE content_sha256 = #{contentSha256}
            """)
    Optional<BankRow> findByContentSha256(@Param("contentSha256") byte[] contentSha256);
}
