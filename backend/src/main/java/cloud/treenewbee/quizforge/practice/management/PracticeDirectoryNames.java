package cloud.treenewbee.quizforge.practice.management;

import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.springframework.http.HttpStatus;

public final class PracticeDirectoryNames {
    private PracticeDirectoryNames() {}

    public static String title(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().codePointCount(0, value.trim().length()) > 200) {
            throw invalid("题库名称须为 1～200 个字符");
        }
        return value.trim();
    }

    /** 沿用旧宿主 library-management.js 的 folderName 规则。 */
    public static String group(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.matches("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*") || value.startsWith(".") || value.endsWith(" ") || value.endsWith(".")
                || value.length() > 120 || value.matches("(?s).*[\\\\/:*?\"<>|\\x00-\\x1f\\x7f-\\x9f].*")
                || value.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")
                || java.util.Set.of("bank.json", "manifest.json", "development.json").contains(value.toLowerCase(java.util.Locale.ROOT))) {
            throw invalid("分组名不符合文件夹命名规则（最长 120 字符）");
        }
        return value;
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
