package cloud.treenewbee.quizforge.identity.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import cloud.treenewbee.quizforge.identity.domain.SessionToken;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.SessionRow;
import cloud.treenewbee.quizforge.identity.web.SessionCookies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = SessionCookies.NAME;
    private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationFilter.class);
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration IDLE_TIMEOUT = Duration.ofDays(14);

    private final SessionMapper sessions;
    private final Clock clock;

    public SessionAuthenticationFilter(SessionMapper sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = SessionCookies.read(request);
        if (SessionToken.isWellFormed(token)) {
            Instant now = clock.instant();
            sessions.findActive(SessionToken.hash(token), now).ifPresent(session -> {
                authenticate(session.userId());
                touchIfNeeded(session, now);
            });
        }
        filterChain.doFilter(request, response);
    }

    private void touchIfNeeded(SessionRow session, Instant now) {
        if (!session.lastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            return;
        }
        Instant idleExpiresAt = now.plus(IDLE_TIMEOUT);
        if (idleExpiresAt.isAfter(session.absoluteExpiresAt())) {
            idleExpiresAt = session.absoluteExpiresAt();
        }
        try {
            if (sessions.touch(session.tokenHash(), now, idleExpiresAt) == 0) {
                log.warn("Session idle refresh affected no rows for user {}", session.userId());
            }
        } catch (RuntimeException error) {
            log.warn("Session idle refresh failed for user {}", session.userId(), error);
        }
    }

    private static void authenticate(String userId) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
