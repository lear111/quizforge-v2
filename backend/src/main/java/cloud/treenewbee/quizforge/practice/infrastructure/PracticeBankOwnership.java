package cloud.treenewbee.quizforge.practice.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import cloud.treenewbee.quizforge.notification.application.BankOwnership;

/**
 * 通知模块的题库接收者筛选：用户未删除的题库实例中来自题库广场的题库标识
 * （source_bank_key）。走 idx_collection_user (user_id, deleted_at, …)。
 */
@Mapper
public interface PracticeBankOwnership extends BankOwnership {

    @Override
    @Select("""
            SELECT DISTINCT source_bank_key FROM practice_collection
             WHERE user_id = #{userId} AND deleted_at IS NULL AND source_bank_key IS NOT NULL
             LIMIT #{limit}
            """)
    List<String> ownedBankKeys(@Param("userId") String userId, @Param("limit") int limit);
}
