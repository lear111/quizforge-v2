package cloud.treenewbee.quizforge.notification.application;

import java.util.List;

/**
 * 题库通知的接收者筛选：用户当前拥有（未删除）、从题库广场加入的题库的 UUID（bank.json 中的 id，设计 04 第 4 节）。
 * 由拥有题库数据的模块实现（practice 模块，按 practice_collection.source_bank_uuid）。
 * 私人导入的题库不参与：它们不随题库包更新，UUID 也可能与广场上的题库相同。
 */
public interface BankOwnership {

    /** 该用户拥有的题库 UUID，最多返回 limit 个。 */
    List<String> ownedBankUuids(String userId, int limit);
}
