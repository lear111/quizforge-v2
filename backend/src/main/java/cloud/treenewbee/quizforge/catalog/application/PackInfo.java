package cloud.treenewbee.quizforge.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 广场信息（沿用旧 Hub 的上限，按 UTF-16 单元计）：名称 ≤ 80、简介 ≤ 240、详细说明 ≤ 12000（都必填）；
 * 标签最多 5 个、合计 ≤ 100 字符；分类从固定列表中选择；更新说明 ≤ 2000（第 1 版可省略）。
 * 截图最多 6 张、每张 ≤ 3 MiB，只接受 PNG / JPEG / WebP（按文件头判断）。
 */
public record PackInfo(String title, String summary, String description, String category, List<String> tags,
                       String releaseNotes) {

    /** 分类（第一版的固定列表，见 PR 说明，由作者确认）。 */
    public static final List<String> CATEGORIES = List.of("语言", "数学", "理工", "计算机", "人文社科", "考试认证", "其他");
    public static final int MAX_SCREENSHOTS = 6;
    public static final long MAX_SCREENSHOT_BYTES = 3L * 1024 * 1024;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    public static PackInfo of(String title, String summary, String description, String category, String tags,
                              String releaseNotes) {
        String name = line(title, 80, "名称");
        String brief = line(summary, 240, "简介");
        String detail = text(description, 12000, "详细说明", true);
        String kind = category == null || category.isBlank() ? "其他" : category.strip();
        if (!CATEGORIES.contains(kind)) throw invalid("分类无效");
        Set<String> parsed = new LinkedHashSet<>();
        String raw = tags == null ? "" : tags;
        if (raw.length() > 200) throw invalid("标签过长");
        for (String tag : raw.split("[,，]")) {
            String value = tag.strip();
            if (value.isEmpty()) continue;
            if (CONTROL.matcher(value).find() || LINE_BREAK.matcher(value).find() || value.length() > 20) throw invalid("标签无效：" + value);
            parsed.add(value);
        }
        if (parsed.size() > 5) throw invalid("最多添加 5 个标签");
        if (parsed.stream().mapToInt(String::length).sum() > 100) throw invalid("标签合计不能超过 100 个字符");
        String notes = text(releaseNotes, 2000, "更新说明", false);
        return new PackInfo(name, brief, detail, kind, List.copyOf(parsed), notes);
    }

    /** 截图类型（按文件头）：png / jpeg / webp；其他返回 null。 */
    public static String imageMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return null;
    }

    /** 截图文件名：去掉路径，不含控制字符，≤ 180。 */
    public static String fileName(String raw, String fallback) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > 180 || CONTROL.matcher(name).find() || LINE_BREAK.matcher(name).find()) return fallback;
        return name;
    }

    private static String line(String value, int max, String label) {
        String text = text(value, max, label, true);
        if (LINE_BREAK.matcher(text).find()) throw invalid(label + "不能换行");
        return text;
    }

    private static String text(String value, int max, String label, boolean required) {
        String text = value == null ? "" : value.strip();
        if (required && text.isEmpty()) throw invalid("请填写" + label);
        if (text.length() > max) throw invalid(label + "不能超过 " + max + " 个字符");
        if (CONTROL.matcher(text).find()) throw invalid(label + "包含无效字符");
        return text;
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "PACK_INFO_INVALID", message);
    }

    static List<String> list(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }
}
