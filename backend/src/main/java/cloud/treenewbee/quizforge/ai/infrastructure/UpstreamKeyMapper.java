package cloud.treenewbee.quizforge.ai.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 上游 API Key 池（ai_upstream_key）。 */
@Mapper
public interface UpstreamKeyMapper {

    record KeyRow(String id, long seq, int position, String note, String keyCipher, String keyHint, long inputPrice,
                  long outputPrice, boolean enabled, Instant expiresAt, String state, String lastError,
                  Instant lastErrorAt, long calls, long inputTokens, long outputTokens, long spent, Instant createdAt) {
    }

    String COLUMNS = """
            id, seq, position, note, key_cipher, key_hint, input_price, output_price, enabled, expires_at, state,
            last_error, last_error_at, calls, input_tokens, output_tokens, spent, created_at""";

    @Select("SELECT " + COLUMNS + " FROM ai_upstream_key ORDER BY position, seq")
    List<KeyRow> all();

    @Select("SELECT " + COLUMNS + " FROM ai_upstream_key WHERE id = #{id}")
    Optional<KeyRow> find(@Param("id") String id);

    /** 参与调用的 Key：已启用、状态可用、未过期，按顺序。 */
    @Select("SELECT " + COLUMNS + " FROM ai_upstream_key WHERE enabled = TRUE AND state = 'ok' "
            + "AND (expires_at IS NULL OR expires_at > #{now}) ORDER BY position, seq LIMIT #{limit}")
    List<KeyRow> usable(@Param("now") Instant now, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_upstream_key")
    int count();

    // 不加锁：两位管理员同时新增时由 seq 唯一约束拒绝其中一个（提示重试）。
    @Select("SELECT COALESCE(MAX(seq), 0) FROM ai_upstream_key")
    long maxSeq();

    @Select("SELECT COALESCE(MAX(position), 0) FROM ai_upstream_key")
    int maxPosition();

    @Insert("""
            INSERT INTO ai_upstream_key (id, seq, position, note, key_cipher, key_hint, input_price, output_price,
                                         enabled, expires_at, state, created_by, created_at, updated_at)
            VALUES (#{id}, #{seq}, #{position}, #{note}, #{cipher}, #{hint}, #{inputPrice}, #{outputPrice},
                    TRUE, #{expiresAt}, 'ok', #{createdBy}, #{now}, #{now})
            """)
    int insert(@Param("id") String id, @Param("seq") long seq, @Param("position") int position,
               @Param("note") String note, @Param("cipher") String cipher, @Param("hint") String hint,
               @Param("inputPrice") long inputPrice, @Param("outputPrice") long outputPrice,
               @Param("expiresAt") Instant expiresAt, @Param("createdBy") String createdBy, @Param("now") Instant now);

    /** 启用时同时恢复可用状态（管理员确认 Key 已修好）。 */
    @Update("""
            <script>
            UPDATE ai_upstream_key SET enabled = #{enabled}, updated_at = #{now}
                   <if test="enabled">, state = 'ok'</if>
             WHERE id = #{id}
            </script>
            """)
    int setEnabled(@Param("id") String id, @Param("enabled") boolean enabled, @Param("now") Instant now);

    @Update("UPDATE ai_upstream_key SET input_price = #{inputPrice}, output_price = #{outputPrice}, updated_at = #{now} "
            + "WHERE id = #{id}")
    int setPrices(@Param("id") String id, @Param("inputPrice") long inputPrice, @Param("outputPrice") long outputPrice,
                  @Param("now") Instant now);

    @Update("UPDATE ai_upstream_key SET position = #{position}, updated_at = #{now} WHERE id = #{id}")
    int setPosition(@Param("id") String id, @Param("position") int position, @Param("now") Instant now);

    @Delete("DELETE FROM ai_upstream_key WHERE id = #{id}")
    int delete(@Param("id") String id);

    /** 记录最近错误；unavailable 为 true 时同时把 Key 标为不可用（Key 本身被拒绝）。 */
    @Update("""
            <script>
            UPDATE ai_upstream_key SET last_error = #{error}, last_error_at = #{now}
                   <if test="unavailable">, state = 'unavailable'</if>
             WHERE id = #{id}
            </script>
            """)
    int recordError(@Param("id") String id, @Param("error") String error, @Param("unavailable") boolean unavailable,
                       @Param("now") Instant now);

    @Update("""
            UPDATE ai_upstream_key SET calls = calls + 1, input_tokens = input_tokens + #{inputTokens},
                   output_tokens = output_tokens + #{outputTokens}, spent = spent + #{cost}
             WHERE id = #{id}
            """)
    int recordUsage(@Param("id") String id, @Param("inputTokens") long inputTokens,
                    @Param("outputTokens") long outputTokens, @Param("cost") long cost);

    @Update("UPDATE ai_upstream_key SET state = 'ok' WHERE id = #{id}")
    int markOk(@Param("id") String id);
}
