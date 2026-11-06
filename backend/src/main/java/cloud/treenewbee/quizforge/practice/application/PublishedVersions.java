package cloud.treenewbee.quizforge.practice.application;

import java.util.Collection;
import java.util.Map;

/**
 * 题库广场的已审核版本（设计 04 第 3 节）。由 catalog 模块实现，practice 模块只通过它读取广场数据。
 * 只看已审核版本：新版本审核期间仍返回上一个已审核版本。
 */
public interface PublishedVersions {

    /**
     * 一个广场题库包的当前状态。
     *
     * @param deleted          发布者已删除整个包（或包不再公开）
     * @param approvedRevision 当前已审核版本；null 表示没有可用的已审核版本
     * @param banks            已审核版本中、调用方关心的题库：bank_uuid → 共享内容 content_bank.id
     */
    record Latest(boolean deleted, Integer approvedRevision, Map<String, String> banks) {
    }

    /** 按包查询；uuids 为调用方关心的 bank_uuid（只返回这些 UUID 在已审核版本中的内容）。 */
    Map<String, Latest> latest(Collection<String> packIds, Collection<String> uuids);
}
