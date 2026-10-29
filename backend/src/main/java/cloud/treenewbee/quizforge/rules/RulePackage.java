package cloud.treenewbee.quizforge.rules;

/**
 * 协议请求中的 package 字段（rule-runner 协议 3.1 节）：要执行哪个拓展，以及它的规则与 Schema 文件。
 * 文件按内容哈希引用；嵌套 record 序列化后正好是协议要求的 {"sha256":"…"} 形状。
 */
public record RulePackage(
        String digest,
        ApiVersion apiVersion,
        boolean outlineItemsDeclared,
        boolean answerStateDeclared,
        FileRef rules,
        FileRef questionSchema,
        FileRef answerSchema) {

    public record ApiVersion(int major, int minor) {
    }

    public record FileRef(String sha256) {
    }
}
