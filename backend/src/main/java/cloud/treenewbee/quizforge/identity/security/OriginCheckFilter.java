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

public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> DEVICE_PATHS = Set.of("/api/connect/token", "/api/connect/redeem", "/api/connect/logout",
            "/api/ai/v1/chat/completions");
    /** 匿名只读：非 GET 由控制器按契约返回 405。 */
    private static final java.util.regex.Pattern LOCAL_IMPORT = java.util.regex.Pattern.compile("/api/packs/[^/]+/local-import");

    private final String allowedOrigin;

    public OriginCheckFilter(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 只对写请求检查来源。设备接口由令牌认证、不使用 Cookie，客户端也不发送 Origin（遗留契约 C-05）。
        if (SAFE_METHODS.contains(request.getMethod()) || DEVICE_PATHS.contains(request.getRequestURI())
                || LOCAL_IMPORT.matcher(request.getRequestURI()).matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");

        // 写请求必须来自本站，替代 CSRF Token（ADR-005）
        if (!allowedOrigin.equals(origin)) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":{\"code\":\"ORIGIN_REJECTED\",\"message\":\"请求来源不受信任\"}}");
    }
}
