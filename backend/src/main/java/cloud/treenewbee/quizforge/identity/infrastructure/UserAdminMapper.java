package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理后台的用户查询（只读）。 */
@Mapper
public interface UserAdminMapper {

    record SummaryRow(String id, String name, String email, String phone, boolean isAdmin, boolean blocked,
                      Instant createdAt) {
    }

    record DetailRow(String id, String name, String email, boolean emailUnverified, String phone,
                     Boolean phoneVerified, Instant phoneVerifiedAt, boolean isAdmin, Instant createdAt,
                     String blockReason, String blockActorId, String blockActorName, Instant blockedAt) {
    }

    /**
     * 按注册时间倒序的键集分页（idx_user_created），可按邮箱、手机号或昵称包含关键字筛选。
     * pattern 为已转义的 LIKE 模式（含首尾 %），为 null 时不筛选。
     */
    @Select("""
            <script>
            SELECT u.id, u.name, u.email, p.phone, u.is_admin,
                   EXISTS (SELECT 1 FROM account_block b WHERE b.user_id = u.id) AS blocked, u.created_at
              FROM account_user u LEFT JOIN account_user_phone p ON p.user_id = u.id
            <where>
              <if test="afterTime != null">
                (u.created_at &lt; #{afterTime} OR (u.created_at = #{afterTime} AND u.id &lt; #{afterId}))
              </if>
              <if test="pattern != null">
                AND (u.email LIKE #{pattern} ESCAPE '!' OR u.name LIKE #{pattern} ESCAPE '!'
                     OR p.phone LIKE #{pattern} ESCAPE '!')
              </if>
            </where>
             ORDER BY u.created_at DESC, u.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<SummaryRow> page(@Param("pattern") String pattern, @Param("afterTime") Instant afterTime,
                          @Param("afterId") String afterId, @Param("limit") int limit);

    @Select("""
            SELECT u.id, u.name, u.email,
                   EXISTS (SELECT 1 FROM account_unverified_contact c
                            WHERE c.user_id = u.id AND c.channel = 'email' AND c.contact = u.email) AS email_unverified,
                   p.phone, p.verified AS phone_verified, p.verified_at AS phone_verified_at,
                   u.is_admin, u.created_at,
                   b.reason AS block_reason, b.actor_id AS block_actor_id, a.name AS block_actor_name,
                   b.created_at AS blocked_at
              FROM account_user u
              LEFT JOIN account_user_phone p ON p.user_id = u.id
              LEFT JOIN account_block b ON b.user_id = u.id
              LEFT JOIN account_user a ON a.id = b.actor_id
             WHERE u.id = #{id}
            """)
    Optional<DetailRow> detail(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM account_user")
    long count();

    record DailyRegistrations(java.time.LocalDate day, long registered) {
    }

    /** 按北京时间的日期统计注册数（created_at 以 UTC 保存，+08:00 换算）。 */
    @Select("""
            SELECT DATE(CONVERT_TZ(created_at, '+00:00', '+08:00')) AS day, COUNT(*) AS registered
              FROM account_user WHERE created_at >= #{since}
             GROUP BY day ORDER BY day
            """)
    List<DailyRegistrations> registrationsSince(@Param("since") Instant since);
}
