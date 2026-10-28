package cloud.treenewbee.quizforge.identity.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String allowedOrigin;

    public OriginCheckFilter(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 如果是安全方法（request.getMethod() 在 SAFE_METHODS 中），直接放行并 return
        if (SAFE_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 读取 request.getHeader("Origin")；不等于 allowedOrigin（包括为 null 的情况）时，
        String origin = request.getHeader("Origin");

        // 写请求必须来自本站，替代 CSRF Token（ADR-005）
        if (!allowedOrigin.equals(origin)) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        // 参照 SecurityConfig 里的 unauthenticated()，返回 403 和
        // {"error":{"code":"ORIGIN_REJECTED","message":"请求来源不受信任"}}
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\"ORIGIN_REJECTED\",\"message\":\"请求来源不受信任\"}}");
    }
}