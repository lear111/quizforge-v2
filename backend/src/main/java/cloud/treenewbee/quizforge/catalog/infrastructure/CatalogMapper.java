package cloud.treenewbee.quizforge.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 题库广场（catalog_ 表）。发布者的写操作都在 SQL 条件里带 owner_id，防止越权（IDOR）。
 */
@Mapper
public interface CatalogMapper {

    record PackRow(String id, String ownerId, int latestRevision, Integer approvedRevision, String status,
                   Instant createdAt, Instant updatedAt, Instant deletedAt) {
    }

    record RevisionRow(String packId, int revision, String title, String summary, String description, String category,
                       String tags, String releaseNotes, String source, String filename, byte[] blobSha256, long size,
                       String extensionDigests, String reviewStatus, String reviewerId, String reviewerName,
                       String reviewNote, Instant reviewedAt, String submittedBy, Instant submittedAt) {
    }

    record PackBankRow(String bankUuid, String bankId, String title, int position, int questionCount, String listening) {
    }

    record FileRow(String id, String packId, int revision, String kind, int position, String name, String mime, long size,
                   byte[] blobSha256) {
    }

    record EventRow(String id, int revisionOrZero, String actorId, String actorName, String action, String detail,
                    Instant createdAt) {
    }

    record PendingRow(String packId, int revision, String title, String ownerId, String ownerName, Instant submittedAt,
                      Integer approvedRevision, long size) {
    }

    // ---------------------------------------------------------------- 题库包

    @Insert("""
            INSERT INTO catalog_pack (id, owner_id, latest_revision, approved_revision, status, created_at, updated_at)
            VALUES (#{id}, #{ownerId}, 1, NULL, 'active', #{now}, #{now})
            """)
    int insertPack(@Param("id") String id, @Param("ownerId") String ownerId, @Param("now") Instant now);

    String PACK = "id, owner_id, latest_revision, approved_revision, status, created_at, updated_at, deleted_at";

    @Select("SELECT " + PACK + " FROM catalog_pack WHERE id = #{id}")
    Optional<PackRow> pack(@Param("id") String id);

    @Select("SELECT " + PACK + " FROM catalog_pack WHERE id=#{id} AND owner_id=#{user} AND status='active'")
    Optional<PackRow> ownedActive(@Param("id") String id, @Param("user") String user);

    /** 发布者自己的、未删除的题库包，加锁：同一个包的新版本、删除与审核互斥。 */
    @Select("SELECT " + PACK + " FROM catalog_pack WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'active' FOR UPDATE")
    Optional<PackRow> lockOwned(@Param("id") String id, @Param("ownerId") String ownerId);

    @Select("SELECT " + PACK + " FROM catalog_pack WHERE id = #{id} FOR UPDATE")
    Optional<PackRow> lock(@Param("id") String id);

    @Update("UPDATE catalog_pack SET latest_revision = #{revision}, updated_at = #{now} WHERE id = #{id} AND latest_revision = #{revision} - 1")
    int advance(@Param("id") String id, @Param("revision") int revision, @Param("now") Instant now);

    @Update("UPDATE catalog_pack SET approved_revision = #{revision}, updated_at = #{now} WHERE id = #{id} AND status = 'active'")
    int approve(@Param("id") String id, @Param("revision") int revision, @Param("now") Instant now);

    @Update("""
            UPDATE catalog_pack SET status = 'deleted', deleted_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'active'
            """)
    int delete(@Param("id") String id, @Param("ownerId") String ownerId, @Param("now") Instant now);

    @Select("SELECT " + PACK + " FROM catalog_pack WHERE owner_id = #{ownerId} AND visibility='public' ORDER BY created_at DESC, id LIMIT 200")
    List<PackRow> owned(@Param("ownerId") String ownerId);

    // ---------------------------------------------------------------- 版本

    @Insert("""
            INSERT INTO catalog_pack_revision (pack_id, revision, title, summary, description, category, tags, release_notes,
                                               source, filename, blob_sha256, size, extension_digests, review_status,
                                               submitted_by, submitted_at)
            VALUES (#{packId}, #{revision}, #{title}, #{summary}, #{description}, #{category}, #{tags}, #{releaseNotes},
                    #{source}, #{filename}, #{blobSha256}, #{size}, #{extensionDigests}, 'pending', #{submittedBy}, #{submittedAt})
            """)
    int insertRevision(RevisionRow row);

    String REVISION = """
            r.pack_id, r.revision, r.title, r.summary, r.description, r.category, r.tags, r.release_notes, r.source,
            r.filename, r.blob_sha256, r.size, r.extension_digests, r.review_status, r.reviewer_id, u.name AS reviewer_name,
            r.review_note, r.reviewed_at, r.submitted_by, r.submitted_at""";

    @Select("SELECT " + REVISION + " FROM catalog_pack_revision r LEFT JOIN account_user u ON u.id = r.reviewer_id "
            + "WHERE r.pack_id = #{packId} AND r.revision = #{revision}")
    Optional<RevisionRow> revision(@Param("packId") String packId, @Param("revision") int revision);

    @Select("SELECT " + REVISION + " FROM catalog_pack_revision r LEFT JOIN account_user u ON u.id = r.reviewer_id "
            + "WHERE r.pack_id = #{packId} ORDER BY r.revision DESC")
    List<RevisionRow> revisions(@Param("packId") String packId);

    /** 上传新版本时，尚未审核的旧版本不再审核（每个版本只审核一次，最新提交的版本代替它）。 */
    @Update("UPDATE catalog_pack_revision SET review_status = #{status} WHERE pack_id = #{packId} AND review_status = 'pending'")
    int closePending(@Param("packId") String packId, @Param("status") String status);

    @Update("""
            UPDATE catalog_pack_revision SET review_status = #{status}, reviewer_id = #{reviewerId}, review_note = #{note},
                   reviewed_at = #{now}
             WHERE pack_id = #{packId} AND revision = #{revision} AND review_status = 'pending'
            """)
    int decide(@Param("packId") String packId, @Param("revision") int revision, @Param("status") String status,
               @Param("reviewerId") String reviewerId, @Param("note") String note, @Param("now") Instant now);

    @Select("""
            SELECT r.pack_id, r.revision, r.title, p.owner_id, u.name AS owner_name, r.submitted_at, p.approved_revision, r.size
              FROM catalog_pack_revision r JOIN catalog_pack p ON p.id = r.pack_id
              LEFT JOIN account_user u ON u.id = p.owner_id
             WHERE r.review_status = 'pending' AND p.status = 'active' AND p.visibility='public' AND r.validation_status='validated'
             ORDER BY r.submitted_at, r.pack_id LIMIT 200
            """)
    List<PendingRow> pending();

    // ---------------------------------------------------------------- 包内题库、附件与记录

    @Insert("""
            <script>
            INSERT INTO catalog_pack_bank (pack_id, revision, bank_uuid, bank_id, title, position) VALUES
            <foreach collection="banks" item="b" separator=",">
              (#{packId}, #{revision}, #{b.bankUuid}, #{b.bankId}, #{b.title}, #{b.position})
            </foreach>
            </script>
            """)
    int insertBanks(@Param("packId") String packId, @Param("revision") int revision, @Param("banks") List<PackBankRow> banks);

    @Select("""
            SELECT pb.bank_uuid, pb.bank_id, pb.title, pb.position, b.question_count, b.listening
              FROM catalog_pack_bank pb JOIN content_bank b ON b.id = pb.bank_id
             WHERE pb.pack_id = #{packId} AND pb.revision = #{revision}
             ORDER BY pb.position
            """)
    List<PackBankRow> banks(@Param("packId") String packId, @Param("revision") int revision);

    @Insert("""
            INSERT INTO catalog_pack_file (id, pack_id, revision, kind, position, name, mime, size, blob_sha256)
            VALUES (#{id}, #{packId}, #{revision}, #{kind}, #{position}, #{name}, #{mime}, #{size}, #{blobSha256})
            """)
    int insertFile(FileRow row);

    @Select("""
            SELECT id, pack_id, revision, kind, position, name, mime, size, blob_sha256 FROM catalog_pack_file
             WHERE pack_id = #{packId} AND revision = #{revision} ORDER BY position
            """)
    List<FileRow> files(@Param("packId") String packId, @Param("revision") int revision);

    @Select("SELECT id, pack_id, revision, kind, position, name, mime, size, blob_sha256 FROM catalog_pack_file WHERE id = #{id}")
    Optional<FileRow> file(@Param("id") String id);

    @Insert("""
            INSERT INTO catalog_pack_event (id, pack_id, revision, actor_id, action, detail, created_at)
            VALUES (#{id}, #{packId}, #{revision}, #{actorId}, #{action}, #{detail}, #{now})
            """)
    int insertEvent(@Param("id") String id, @Param("packId") String packId, @Param("revision") Integer revision,
                    @Param("actorId") String actorId, @Param("action") String action, @Param("detail") String detail,
                    @Param("now") Instant now);

    @Select("""
            SELECT e.id, COALESCE(e.revision, 0) AS revision_or_zero, e.actor_id, u.name AS actor_name, e.action, e.detail,
                   e.created_at
              FROM catalog_pack_event e LEFT JOIN account_user u ON u.id = e.actor_id
             WHERE e.pack_id = #{packId} ORDER BY e.created_at, e.id
            """)
    List<EventRow> events(@Param("packId") String packId);

    // ---------------------------------------------------------------- 加入我的题库

    /** 已加入的同一本题库（重复加入时打开已有的实例）。 */
    @Select("""
            SELECT id FROM practice_collection
             WHERE user_id = #{userId} AND source_pack_id = #{packId} AND source_bank_uuid = #{bankUuid} AND deleted_at IS NULL
             ORDER BY created_at LIMIT 1
            """)
    Optional<String> joined(@Param("userId") String userId, @Param("packId") String packId, @Param("bankUuid") String bankUuid);
}
