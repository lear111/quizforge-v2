package cloud.treenewbee.quizforge.practice.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PracticeHistoryMapper {
    record Header(String id,String userId,String collectionId,String collectionSnapshot,String summary,Instant createdAt,Instant updatedAt) {}
    record Digest(String id,String collectionTitle,String mode,String status,int questionCount,int submittedCount,int pendingCount,
            java.math.BigDecimal score,java.math.BigDecimal maxScore,Instant createdAt,Instant updatedAt) {}
    @Select("""
        SELECT r.id,JSON_UNQUOTE(JSON_EXTRACT(r.collection_snapshot,'$.title')) AS collection_title,r.mode,r.status,
          COALESCE(JSON_EXTRACT(r.summary,'$.questionCount'),0) AS question_count,
          COALESCE(JSON_EXTRACT(r.summary,'$.submittedCount'),0) AS submitted_count,
          COALESCE(JSON_EXTRACT(r.summary,'$.pendingCount'),0) AS pending_count,
          COALESCE(JSON_EXTRACT(r.summary,'$.score'),0) AS score,COALESCE(JSON_EXTRACT(r.summary,'$.maxScore'),0) AS max_score,
          r.created_at,r.updated_at
        FROM practice_round r JOIN practice_collection c ON c.id=r.collection_id AND c.user_id=r.user_id
        WHERE r.user_id=#{userId} AND r.collection_id=#{collectionId} AND r.deleted_at IS NULL
          AND c.deleted_at IS NULL AND r.history_frozen_at IS NOT NULL
        ORDER BY r.history_frozen_at DESC,r.id DESC
        """)
    List<Digest> list(@Param("userId") String userId,@Param("collectionId") String collectionId);
    @Select("""
        SELECT r.id,r.user_id,r.collection_id,r.collection_snapshot,r.summary,r.created_at,r.updated_at
        FROM practice_round r JOIN practice_collection c ON c.id=r.collection_id AND c.user_id=r.user_id
        WHERE r.id=#{id} AND r.user_id=#{userId} AND r.collection_id=#{collectionId} AND r.deleted_at IS NULL
          AND c.deleted_at IS NULL AND r.history_frozen_at IS NOT NULL
        """)
    Optional<Header> find(@Param("id") String id,@Param("userId") String userId,@Param("collectionId") String collectionId);
    @Select("SELECT listening FROM content_bank WHERE id=#{id}")
    Optional<String> listening(@Param("id") String id);
    @Select("SELECT id FROM practice_round WHERE id=#{id} AND user_id=#{userId} AND collection_id=#{collectionId} AND history_frozen_at IS NOT NULL AND deleted_at IS NULL FOR UPDATE")
    Optional<String> lock(@Param("id") String id,@Param("userId") String userId,@Param("collectionId") String collectionId);
    @Select("SELECT question_id,payload,extension_digest FROM practice_round_question WHERE round_id=#{id} AND user_id=#{userId} ORDER BY question_id")
    List<Entry> entries(@Param("id") String id,@Param("userId") String userId);
    record Entry(String questionId,String payload,byte[] extensionDigest) {}
    @Select("SELECT payload FROM practice_round_question WHERE round_id=#{id} AND user_id=#{userId} AND question_id=#{qid} AND BINARY question_id=BINARY #{qid}")
    Optional<String> payload(@Param("id") String id,@Param("userId") String userId,@Param("qid") String qid);
    @Select("SELECT notes,revision FROM practice_history_annotation WHERE history_id=#{id} AND question_id=#{qid} AND user_id=#{userId}")
    Optional<Annotation> annotation(@Param("id") String id,@Param("qid") String qid,@Param("userId") String userId);
    record Annotation(String notes,long revision) {}
    @Insert("""
        INSERT INTO practice_history_annotation(history_id,question_id,user_id,notes,revision,created_at,updated_at)
        VALUES(#{id},#{qid},#{userId},#{notes},#{revision},#{now},#{now})
        ON DUPLICATE KEY UPDATE notes=#{notes},revision=#{revision},updated_at=#{now}
        """)
    int annotate(@Param("id") String id,@Param("qid") String qid,@Param("userId") String userId,@Param("notes") String notes,
            @Param("revision") long revision,@Param("now") Instant now);
    @Update("UPDATE practice_round SET deleted_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL")
    int delete(@Param("id") String id,@Param("userId") String userId,@Param("now") Instant now);
    @Delete("DELETE FROM practice_history_annotation WHERE history_id=#{id} AND user_id=#{userId}")
    int deleteAnnotations(@Param("id") String id,@Param("userId") String userId);
    @Insert("""
        INSERT IGNORE INTO practice_history_asset(history_id,blob_sha256,path)
        SELECT #{id},blob_sha256,path FROM content_bank_asset WHERE bank_id=#{bankId}
        """)
    int copyAssets(@Param("id") String id,@Param("bankId") String bankId);
}
