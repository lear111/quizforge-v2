package cloud.treenewbee.quizforge.identity.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 账号的联系方式：邮箱或中国大陆手机号。
 *
 * <p>规范化规则与旧平台一致（C-06、Issue #20）：邮箱去首尾空白后转小写；手机号去掉空格与 "-"、
 * 去掉开头的 +86 / 86，必须为 1[3-9] 开头的 11 位号码，统一存为 "+86" + 11 位。
 */
public record Contact(Channel channel, String value) {

    public enum Channel {
        EMAIL, PHONE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    private static final Pattern EMAIL = Pattern.compile("[^\\s\\p{Z}\\uFEFF@]+@[^\\s\\p{Z}\\uFEFF@]+\\.[^\\s\\p{Z}\\uFEFF@]+");
    private static final Pattern PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern PHONE_PREFIX = Pattern.compile("^(?:\\+86|86)(?=1\\d{10}$)");

    public Contact {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(value, "value");
    }

    public static Contact email(String raw) {
        Contact contact = parse(raw, Channel.EMAIL);
        if (contact == null) throw invalid();
        return contact;
    }

    public static Contact phone(String raw) {
        Contact contact = parse(raw, Channel.PHONE);
        if (contact == null) throw invalid();
        return contact;
    }

    /** 自动识别邮箱或手机号。 */
    public static Contact identifier(String raw) {
        Contact contact = parse(raw, null);
        if (contact == null) throw invalid();
        return contact;
    }

    /** 请求体里 identifier / email / phone 三选一。 */
    public static Contact fromRequest(String identifier, String email, String phone) {
        int present = (identifier != null ? 1 : 0) + (email != null ? 1 : 0) + (phone != null ? 1 : 0);
        if (present != 1) throw invalid();
        if (email != null) return email(email);
        if (phone != null) return phone(phone);
        return identifier(identifier);
    }

    private static Contact parse(String raw, Channel only) {
        if (raw == null) return null;
        String value = EDGE_WHITESPACE.matcher(raw).replaceAll("");
        if (only != Channel.PHONE && value.length() <= 180 && EMAIL.matcher(value).matches()) {
            return new Contact(Channel.EMAIL, value.toLowerCase(Locale.ROOT));
        }
        if (only != Channel.EMAIL && value.length() <= 32) {
            String digits = PHONE_PREFIX.matcher(value.replace(" ", "").replace("-", "")).replaceFirst("");
            if (PHONE.matcher(digits).matches()) return new Contact(Channel.PHONE, "+86" + digits);
        }
        return null;
    }

    public boolean isEmail() {
        return channel == Channel.EMAIL;
    }

    /** 日志和审计中使用的脱敏形式。 */
    public String masked() {
        if (isEmail()) {
            int at = value.indexOf('@');
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.substring(0, 6) + "****" + value.substring(10);
    }

    // 与其他字段校验失败使用同一错误码和提示（API 设计 1.3）；具体提示由前端在提交前给出。
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求内容不符合要求");
    }
}
