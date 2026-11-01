package cloud.treenewbee.quizforge.identity.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 按客户端 IP 限制匿名认证接口（登录、注册、发送验证码、找回密码），共用一个额度。
 * 超限返回 429 与 Retry-After。
 *
 * <p>客户端 IP 取 {@code request.getRemoteAddr()}：应用只在 Nginx 之后运行（端口不对外发布），
 * Tomcat 的 RemoteIpValve（server.forward-headers-strategy=native）只在直接对端是内网代理时
 * 才采用 X-Forwarded-For；Nginx 用 {@code $remote_addr} 覆盖该头，不转发客户端自带的值。
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final Set<String> PATHS = Set.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/login-code",
            "/api/auth/email-code", "/api/auth/phone-code",
            "/api/auth/password-reset-code", "/api/auth/password-reset");

    private final AuthRateLimiter limiter;

    public AuthRateLimitFilter(AuthRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var decision = limiter.tryAcquire(request.getRemoteAddr());
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}}");
    }
}
