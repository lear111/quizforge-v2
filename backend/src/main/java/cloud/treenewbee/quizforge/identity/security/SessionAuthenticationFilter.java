package cloud.treenewbee.quizforge.identity.security;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "__Host-qf_session";

    private final SessionMapper sessions;
    private final Clock clock;

    public SessionAuthenticationFilter(SessionMapper sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = readCookie(request);
        if (SessionToken.isWellFormed(token)) {
            // TODO: 查询会话；查到时调用 authenticate(userId)
            sessions.findActive(SessionToken.hash(token), clock.instant())
            .ifPresent(session -> authenticate(session.userId()));

        }
        filterChain.doFilter(request, response);
    }

    private static String readCookie(HttpServletRequest request) {
        // TODO: request.getCookies() 可能返回 null；遍历找到名字等于 COOKIE_NAME 的 Cookie，返回它的值；找不到返回 null
        Cookie[] cookies = request.getCookies();  
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static void authenticate(String userId) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}