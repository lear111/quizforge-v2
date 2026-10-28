package cloud.treenewbee.quizforge.identity.web;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

public final class SessionCookies {

    public static final String NAME = "__Host-qf_session";
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private SessionCookies() {
    }

    public static ResponseCookie create(String token) {
        return cookie(token, MAX_AGE);
    }

    public static ResponseCookie clear() {
        return cookie("", Duration.ZERO);
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
