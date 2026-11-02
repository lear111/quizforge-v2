package cloud.treenewbee.quizforge.content.domain;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 保存与重新打包用的 bank.json：按 JSON.stringify 的语义序列化解析后的作者 JSON（保留字段顺序）。
 * 再次解析得到相同的对象，所以重新打包后的题库内容哈希与原导入相同（设计 06 第 9.1 节），
 * 从“我的题库”分享时可以复用已存储的内容。
 */
public final class BankJson {
    private BankJson() {}

    public static byte[] bytes(Map<String, Object> bank) {
        return EcmaJson.stringify(bank, false).getBytes(StandardCharsets.UTF_8);
    }
}
