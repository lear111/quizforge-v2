package cloud.treenewbee.quizforge.practice.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import cloud.treenewbee.quizforge.notification.application.BankOwnership;

/**
 * 通知模块的题库更新通知接收者筛选：用户未删除、从题库广场导入（source_pack_id 不为空）的题库实例所属的题库包。
 * 走 idx_collection_user (user_id, deleted_at, …)。
 */
@Mapper
public interface PracticeBankOwnership extends BankOwnership {

    @Override
    @Select("""
            SELECT DISTINCT source_pack_id FROM practice_collection
             WHERE user_id = #{userId} AND deleted_at IS NULL AND source_pack_id IS NOT NULL
             LIMIT #{limit}
            """)
    List<String> ownedPackIds(@Param("userId") String userId, @Param("limit") int limit);
}
