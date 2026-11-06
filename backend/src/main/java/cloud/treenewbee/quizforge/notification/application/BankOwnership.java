package cloud.treenewbee.quizforge.notification.application;

import java.util.List;

/**
 * 题库更新通知的接收者筛选：用户当前拥有（未删除）、从题库广场导入的实例所属的题库包（source_pack_id，设计 04 第 3.3 节）。
 * 由拥有题库数据的模块实现（practice 模块）。按包而不是按 bank.json 的 UUID 判断：不同包之间 UUID 可能相同，
 * 按 UUID 会把通知发给其他包的用户；私人导入的题库不随广场更新，也不接收。
 */
public interface BankOwnership {

    /** 该用户拥有实例的广场题库包 ID，最多返回 limit 个。 */
    List<String> ownedPackIds(String userId, int limit);
}
