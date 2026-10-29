package cloud.treenewbee.quizforge.content.domain;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Shared predicates from design 06 appendix A; lengths use UTF-16 units, as in the legacy host. */
final class PackageRules {
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final Pattern BLANK = Pattern.compile("[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*");
    private static final Pattern EDGES = Pattern.compile("^[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]|[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]$");
    private static final Pattern NON_PLAIN = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f<>]");
    private PackageRules() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, Consumer<String> fail) {
        if (!(value instanceof Map<?, ?>)) fail.accept("必须为对象");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, int min, int max, Consumer<String> fail) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) fail.accept("数组数量必须为 " + min + "～" + max);
        return (List<Object>) value;
    }
    static void keys(Map<String, Object> value, Set<String> allowed, Consumer<String> fail) {
        for (String key : value.keySet()) if (!allowed.contains(key)) fail.accept("不允许的字段：" + key);
    }
    static boolean integer(Object value, int min, int max) {
        return value instanceof BigInteger number && number.compareTo(BigInteger.valueOf(min)) >= 0 && number.compareTo(BigInteger.valueOf(max)) <= 0;
    }
    static boolean text(Object value, int max) {
        return value instanceof String string && !BLANK.matcher(string).matches() && string.length() <= max;
    }
    static boolean plain(Object value, int max) {
        return text(value, max) && !EDGES.matcher((String) value).find() && !NON_PLAIN.matcher((String) value).find();
    }
    static String text(Map<String, Object> value, String key, int max, Consumer<String> fail) {
        if (!text(value.get(key), max)) fail.accept(key + " 必须为非空字符串且不超过 " + max + " 字符");
        return (String) value.get(key);
    }
    static void optionalDescription(Map<String, Object> value, Consumer<String> fail) {
        if (value.containsKey("description") && (!(value.get("description") instanceof String description) || description.length() > 4000)) fail.accept("description 必须为不超过 4000 字符的字符串");
    }
}
