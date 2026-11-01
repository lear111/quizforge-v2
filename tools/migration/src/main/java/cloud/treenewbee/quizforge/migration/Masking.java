package cloud.treenewbee.quizforge.migration;

/** 报告中的脱敏规则（详细设计 09 第 4.4 节）：不出现完整联系方式、昵称与密码哈希。 */
public final class Masking {

    private Masking() {
    }

    /** 用户 ID 只保留前 8 位。 */
    public static String id(String value) {
        if (value == null) return null;
        return value.length() <= 8 ? value : value.substring(0, 8) + "…";
    }

    /** a***@example.com */
    public static String email(String value) {
        if (value == null) return null;
        int at = value.indexOf('@');
        if (at <= 0) return "***";
        return value.charAt(0) + "***" + value.substring(at);
    }

    /** +86138****8000 */
    public static String phone(String value) {
        if (value == null) return null;
        if (value.length() < 11) return "****";
        return value.substring(0, value.length() - 8) + "****" + value.substring(value.length() - 4);
    }

    /** 昵称只保留第一个字符。 */
    public static String name(String value) {
        if (value == null || value.isEmpty()) return "*";
        return new String(Character.toChars(value.codePointAt(0))) + "*";
    }
}
