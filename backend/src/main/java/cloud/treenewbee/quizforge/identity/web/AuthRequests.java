package cloud.treenewbee.quizforge.identity.web;

import java.util.regex.Pattern;

import org.hibernate.validator.constraints.CodePointLength;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

    // 与旧版 Contact.parse 一致，拒绝地址内部的 Unicode 空白。
    private static final String EMAIL_PATTERN = "[^\\s\\p{Z}\\uFEFF@]+@[^\\s\\p{Z}\\uFEFF@]+\\.[^\\s\\p{Z}\\uFEFF@]+";
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");

    private AuthRequests() {
    }

    public record Register(
            @NotBlank @Size(max = 180) @jakarta.validation.constraints.Pattern(regexp = EMAIL_PATTERN) String email,
            @NotBlank @CodePointLength(min = 1, max = 64) String name,
            @NotNull @CodePointLength(min = 8, max = 128) String password) {
        public Register {
            email = trim(email);
            name = trim(name);
        }
    }

    public record Login(
            @NotBlank @Size(max = 180) @jakarta.validation.constraints.Pattern(regexp = EMAIL_PATTERN) String email,
            @NotNull @CodePointLength(min = 8, max = 128) String password) {
        public Login {
            email = trim(email);
        }
    }

    private static String trim(String value) {
        return value == null ? null : EDGE_WHITESPACE.matcher(value).replaceAll("");
    }
}
