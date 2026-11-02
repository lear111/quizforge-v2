package cloud.treenewbee.quizforge.admin.infrastructure;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理员操作审计（admin_audit）：只插入、只读，不修改、不删除。 */
@Mapper
public interface AdminAuditMapper {

    record AuditRow(String id, String actorId, String actorName, String module, String action, String target,
                    String detail, Instant createdAt) {
    }

    @Insert("""
            INSERT INTO admin_audit (id, actor_id, module, action, target, detail, created_at)
            VALUES (#{id}, #{actorId}, #{module}, #{action}, #{target}, #{detail}, #{createdAt})
            """)
    int insert(@Param("id") String id, @Param("actorId") String actorId, @Param("module") String module,
               @Param("action") String action, @Param("target") String target, @Param("detail") String detail,
               @Param("createdAt") Instant createdAt);

    // 键集分页：按 (created_at, id) 倒序，从游标之后继续，走 idx_admin_audit_time。
    @Select("""
            <script>
            SELECT a.id, a.actor_id, u.name AS actor_name, a.module, a.action, a.target, a.detail, a.created_at
              FROM admin_audit a LEFT JOIN account_user u ON u.id = a.actor_id
            <where>
              <if test="afterTime != null">
                a.created_at &lt; #{afterTime} OR (a.created_at = #{afterTime} AND a.id &lt; #{afterId})
              </if>
            </where>
             ORDER BY a.created_at DESC, a.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<AuditRow> page(@Param("afterTime") Instant afterTime, @Param("afterId") String afterId,
                        @Param("limit") int limit);
}
