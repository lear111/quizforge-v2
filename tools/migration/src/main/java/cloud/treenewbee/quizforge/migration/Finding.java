package cloud.treenewbee.quizforge.migration;

/**
 * 报告中的一条发现（详细设计 09 第 4.4 节）。
 *
 * <p>ERROR 阻止迁移与切换；WARN 可迁移但须作者逐条签注；INFO 只是统计或不迁移的残留数据。
 * userId 只写 ID 前 8 位，detail 不含联系方式、密码哈希等个人信息。
 */
public record Finding(Severity severity, String code, String userId, String detail) {

    public enum Severity { ERROR, WARN, INFO }

    public static Finding error(String code, String userId, String detail) {
        return new Finding(Severity.ERROR, code, Masking.id(userId), detail);
    }

    public static Finding warn(String code, String userId, String detail) {
        return new Finding(Severity.WARN, code, Masking.id(userId), detail);
    }

    public static Finding info(String code, String userId, String detail) {
        return new Finding(Severity.INFO, code, Masking.id(userId), detail);
    }
}
