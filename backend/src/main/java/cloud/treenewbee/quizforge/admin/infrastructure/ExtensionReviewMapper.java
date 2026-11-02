package cloud.treenewbee.quizforge.admin.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 拓展审核白名单（admin_extension_review，C-04）。状态：pending 待审核、approved 已批准、
 * rejected 未通过（待审核时拒绝）、revoked 已撤销（批准后撤销）。
 */
@Mapper
public interface ExtensionReviewMapper {
    @Insert("""
            INSERT INTO admin_extension_review (digest, extension_id, version, status, reviewer, reviewed_at, reason)
            VALUES (#{digest}, #{extensionId}, #{version}, #{status}, #{reviewer}, #{reviewedAt}, #{reason})
            """)
    int insert(ExtensionReviewRow row);

    @Select("""
            SELECT digest, extension_id, version, status, reviewer, reviewed_at, reason
              FROM admin_extension_review WHERE digest = #{digest}
            """)
    Optional<ExtensionReviewRow> findByDigest(@Param("digest") byte[] digest);

    /** 导入时登记未知的拓展摘要；已有记录（任何状态）保持不变。 */
    @Insert("""
            INSERT INTO admin_extension_review (digest, extension_id, version, status, created_at)
            VALUES (#{digest}, #{extensionId}, #{version}, 'pending', #{now})
            ON DUPLICATE KEY UPDATE digest = digest
            """)
    int registerPending(@Param("digest") byte[] digest, @Param("extensionId") String extensionId,
                        @Param("version") String version, @Param("now") Instant now);

    record ListRow(byte[] digest, String extensionId, String version, String name, String status, String reviewer,
                   String reviewerName, Instant reviewedAt, String reason, Instant createdAt) {
    }

    @Select("""
            <script>
            SELECT r.digest, r.extension_id, r.version, p.name, r.status, r.reviewer, u.name AS reviewer_name,
                   r.reviewed_at, r.reason, r.created_at
              FROM admin_extension_review r
              LEFT JOIN content_extension_package p ON p.digest = r.digest
              LEFT JOIN account_user u ON u.id = r.reviewer
             <where>
               <if test="status != null">r.status = #{status}</if>
               <if test="digests != null">AND r.digest IN
                 <foreach collection="digests" item="d" open="(" separator="," close=")">#{d}</foreach></if>
             </where>
             ORDER BY r.created_at IS NULL, r.created_at DESC, r.extension_id
             LIMIT #{limit}
            </script>
            """)
    List<ListRow> list(@Param("status") String status, @Param("digests") List<byte[]> digests, @Param("limit") int limit);

    /** 条件更新：只有处于 from 状态之一的记录才会变化，返回影响行数。 */
    @Update("""
            <script>
            UPDATE admin_extension_review SET status = #{status}, reviewer = #{reviewer}, reviewed_at = #{now}, reason = #{reason}
             WHERE digest = #{digest} AND status IN
               <foreach collection="from" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    int decide(@Param("digest") byte[] digest, @Param("from") List<String> from, @Param("status") String status,
               @Param("reviewer") String reviewer, @Param("reason") String reason, @Param("now") Instant now);

    /** 同一拓展 ID 的其他版本（供管理员对照）。 */
    @Select("""
            SELECT r.digest, r.extension_id, r.version, p.name, r.status, r.reviewer, NULL AS reviewer_name,
                   r.reviewed_at, r.reason, r.created_at
              FROM admin_extension_review r LEFT JOIN content_extension_package p ON p.digest = r.digest
             WHERE r.extension_id = #{extensionId} AND r.digest <> #{digest}
             ORDER BY r.created_at DESC LIMIT 10
            """)
    List<ListRow> siblings(@Param("extensionId") String extensionId, @Param("digest") byte[] digest);

    // ---------------------------------------------------------------- 撤销前的影响范围（只读其他模块的表）

    /** 使用该拓展、未删除的私人题库实例数（从题库广场加入的不计，另按题库包列出）。 */
    @Select("""
            SELECT COUNT(*) FROM practice_collection c
             WHERE c.deleted_at IS NULL AND c.source_pack_id IS NULL
               AND c.bank_id IN (SELECT DISTINCT q.bank_id FROM content_question q WHERE q.extension_digest = #{digest})
            """)
    long privateCollections(@Param("digest") byte[] digest);

    record AffectedPack(String packId, int revision, String title, String reviewStatus, boolean current) {
    }

    /** 当前公开版本或待审核版本中用到该拓展的题库包（未删除）；current 表示是当前公开的已审核版本。 */
    @Select("""
            SELECT r.pack_id, r.revision, r.title, r.review_status, (p.approved_revision = r.revision) AS current
              FROM catalog_pack_revision r JOIN catalog_pack p ON p.id = r.pack_id
             WHERE p.status = 'active' AND JSON_CONTAINS(r.extension_digests, JSON_QUOTE(#{digestHex}))
               AND (r.revision = p.approved_revision OR r.review_status = 'pending')
             ORDER BY r.submitted_at DESC LIMIT 200
            """)
    List<AffectedPack> packs(@Param("digestHex") String digestHex);
}
