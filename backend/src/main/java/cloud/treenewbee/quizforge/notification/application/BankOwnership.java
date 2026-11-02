package cloud.treenewbee.quizforge.notification.application;

import java.util.List;

/**
 * 题库通知的接收者筛选：用户当前拥有（未删除）的题库标识。由拥有题库数据的模块实现
 * （当前为 practice 模块，按 practice_collection.source_bank_key）；题库广场完成后可改为按题库 UUID。
 */
public interface BankOwnership {

    /** 该用户拥有的题库标识，最多返回 limit 个。 */
    List<String> ownedBankKeys(String userId, int limit);
}
