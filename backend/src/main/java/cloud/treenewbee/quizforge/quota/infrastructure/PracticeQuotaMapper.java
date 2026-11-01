package cloud.treenewbee.quizforge.quota.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PracticeQuotaMapper {
    record Item(String objectId, String collectionId, long sizeBytes) {}
    @Select("SELECT collection_id FROM practice_context WHERE id = #{contextId} AND user_id = #{userId}")
    String collection(@Param("userId") String userId, @Param("contextId") String contextId);
    // Measure normalized UTF-8 JSON payloads; database indexes and quota/receipt bookkeeping are platform costs.
    String STATE_BYTES = """
            COALESCE(OCTET_LENGTH(CAST(s.answer AS CHAR CHARACTER SET utf8mb4)),0) +
            COALESCE(OCTET_LENGTH(CAST(s.answer_state AS CHAR CHARACTER SET utf8mb4)),0) +
            COALESCE(OCTET_LENGTH(CAST(s.result AS CHAR CHARACTER SET utf8mb4)),0) +
            COALESCE(OCTET_LENGTH(CAST(s.outline_states AS CHAR CHARACTER SET utf8mb4)),0) +
            COALESCE(OCTET_LENGTH(CAST(s.projection AS CHAR CHARACTER SET utf8mb4)),0)
            """;

    @Select("""
            <script>SELECT CONCAT(s.context_id, ':', s.question_id) AS object_id, #{collectionId} AS collection_id,
            """ + STATE_BYTES + """
            AS size_bytes FROM practice_question_state s
            WHERE s.user_id = #{userId} AND s.context_id = #{contextId}
            <if test="qid != null">AND s.question_id = #{qid} AND BINARY s.question_id = BINARY #{qid}</if>
            ORDER BY s.question_id FOR UPDATE</script>
            """)
    List<Item> states(@Param("userId") String userId, @Param("contextId") String contextId, @Param("qid") String qid, @Param("collectionId") String collectionId);

    @Select("""
            SELECT r.id AS object_id, r.collection_id,
              COALESCE(OCTET_LENGTH(CAST(r.collection_snapshot AS CHAR CHARACTER SET utf8mb4)),0) +
              COALESCE(OCTET_LENGTH(CAST(r.summary AS CHAR CHARACTER SET utf8mb4)),0) AS size_bytes
            FROM practice_round r WHERE r.user_id = #{userId} AND r.id = #{roundId}
              AND r.deleted_at IS NULL ORDER BY r.id FOR UPDATE
            """)
    List<Item> rounds(@Param("userId") String userId, @Param("roundId") String roundId);

    @Select("""
            <script>
            SELECT CONCAT(q.round_id, ':', q.question_id) AS object_id, r.collection_id,
              OCTET_LENGTH(CAST(q.payload AS CHAR CHARACTER SET utf8mb4)) AS size_bytes
            FROM practice_round_question q JOIN practice_round r ON r.id = q.round_id AND r.user_id = q.user_id
            WHERE q.user_id = #{userId} AND r.id = #{roundId} AND r.deleted_at IS NULL
            <if test="qid != null">AND q.question_id = #{qid} AND BINARY q.question_id = BINARY #{qid}</if>
            ORDER BY q.round_id, q.question_id FOR UPDATE
            </script>
            """)
    List<Item> snapshots(@Param("userId") String userId, @Param("roundId") String roundId, @Param("qid") String qid);
}
