package cloud.treenewbee.quizforge.notification.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 站内通知。对用户可见 = 发给所有人 / 发给该用户 / 发给该用户拥有的题库（bankKeys）。
 * 三类条件都是 (audience, target_id) 等值，走 idx_notification_audience。
 */
@Mapper
public interface NotificationMapper {

    record NotificationRow(String id, String audience, String targetId, String source, String title, String body,
                           boolean pinned, String createdBy, String createdByName, Instant createdAt) {
    }

    record UserItemRow(String id, String source, String title, boolean pinned, Instant createdAt, boolean read) {
    }

    String VISIBLE = """
            ((n.audience = 'all' AND n.target_id = '')
              OR (n.audience = 'user' AND n.target_id = #{userId})
              <if test="bankKeys != null and !bankKeys.isEmpty()">
              OR (n.audience = 'bank' AND n.target_id IN
                  <foreach collection="bankKeys" item="key" open="(" separator="," close=")">#{key}</foreach>)
              </if>)
            """;

    @Insert("""
            INSERT INTO notification (id, audience, target_id, source, title, body, pinned, created_by, created_at)
            VALUES (#{id}, #{audience}, #{targetId}, #{source}, #{title}, #{body}, #{pinned}, #{createdBy}, #{createdAt})
            """)
    int insert(@Param("id") String id, @Param("audience") String audience, @Param("targetId") String targetId,
               @Param("source") String source, @Param("title") String title, @Param("body") String body,
               @Param("pinned") boolean pinned, @Param("createdBy") String createdBy,
               @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT n.id, n.audience, n.target_id, n.source, n.title, n.body, n.pinned, n.created_by,
                   u.name AS created_by_name, n.created_at
              FROM notification n LEFT JOIN account_user u ON u.id = n.created_by
             WHERE n.id = #{id}
            """)
    Optional<NotificationRow> find(@Param("id") String id);

    @Delete("DELETE FROM notification_read WHERE notification_id = #{id}")
    int deleteReads(@Param("id") String id);

    @Delete("DELETE FROM notification WHERE id = #{id}")
    int delete(@Param("id") String id);

    @Update("UPDATE notification SET pinned = #{pinned} WHERE id = #{id}")
    int setPinned(@Param("id") String id, @Param("pinned") boolean pinned);

    /** 管理员列表：全部通知，按时间倒序键集分页。 */
    @Select("""
            <script>
            SELECT n.id, n.audience, n.target_id, n.source, n.title, n.body, n.pinned, n.created_by,
                   u.name AS created_by_name, n.created_at
              FROM notification n LEFT JOIN account_user u ON u.id = n.created_by
            <where>
              <if test="afterTime != null">
                n.created_at &lt; #{afterTime} OR (n.created_at = #{afterTime} AND n.id &lt; #{afterId})
              </if>
            </where>
             ORDER BY n.created_at DESC, n.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<NotificationRow> adminPage(@Param("afterTime") Instant afterTime, @Param("afterId") String afterId,
                                    @Param("limit") int limit);

    /**
     * 铃铛红点：是否存在比 since 更新、对该用户可见的通知。拆成三个 EXISTS，各自只在
     * idx_notification_audience 上做 (audience, target_id) 等值 + created_at 范围查找，找到一行即停。
     */
    @Select("""
            <script>
            SELECT EXISTS (SELECT 1 FROM notification
                            WHERE audience = 'all' AND target_id = '' AND created_at &gt; #{since})
                OR EXISTS (SELECT 1 FROM notification
                            WHERE audience = 'user' AND target_id = #{userId} AND created_at &gt; #{since})
              <if test="bankKeys != null and !bankKeys.isEmpty()">
                OR EXISTS (SELECT 1 FROM notification
                            WHERE audience = 'bank' AND target_id IN
                            <foreach collection="bankKeys" item="key" open="(" separator="," close=")">#{key}</foreach>
                              AND created_at &gt; #{since})
              </if>
            </script>
            """)
    boolean hasNewer(@Param("userId") String userId, @Param("bankKeys") List<String> bankKeys,
                     @Param("since") Instant since);

    /** 用户的通知列表：置顶在前，其余按时间倒序，带“是否已查看”。 */
    @Select("""
            <script>
            SELECT n.id, n.source, n.title, n.pinned, n.created_at, (r.user_id IS NOT NULL) AS `read`
              FROM notification n
              LEFT JOIN notification_read r ON r.notification_id = n.id AND r.user_id = #{userId}
             WHERE""" + VISIBLE + """
             ORDER BY n.pinned DESC, n.created_at DESC, n.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<UserItemRow> userList(@Param("userId") String userId, @Param("bankKeys") List<String> bankKeys,
                               @Param("limit") int limit);

    @Select("""
            <script>
            SELECT n.id, n.audience, n.target_id, n.source, n.title, n.body, n.pinned, n.created_by,
                   NULL AS created_by_name, n.created_at
              FROM notification n
             WHERE n.id = #{id} AND""" + VISIBLE + """
            </script>
            """)
    Optional<NotificationRow> findVisible(@Param("id") String id, @Param("userId") String userId,
                                          @Param("bankKeys") List<String> bankKeys);

    /** 标记已查看；只能标记对该用户可见的通知（可见性写在 SQL 条件里），重复标记不报错。 */
    @Insert("""
            <script>
            INSERT IGNORE INTO notification_read (user_id, notification_id, read_at)
            SELECT #{userId}, n.id, #{now} FROM notification n
             WHERE n.id = #{id} AND""" + VISIBLE + """
            </script>
            """)
    int markRead(@Param("id") String id, @Param("userId") String userId, @Param("bankKeys") List<String> bankKeys,
                 @Param("now") Instant now);

    @Select("SELECT seen_at FROM notification_seen WHERE user_id = #{userId}")
    Optional<Instant> seenAt(@Param("userId") String userId);

    @Insert("""
            INSERT INTO notification_seen (user_id, seen_at) VALUES (#{userId}, #{now})
            ON DUPLICATE KEY UPDATE seen_at = GREATEST(seen_at, VALUES(seen_at))
            """)
    int markSeen(@Param("userId") String userId, @Param("now") Instant now);
}
