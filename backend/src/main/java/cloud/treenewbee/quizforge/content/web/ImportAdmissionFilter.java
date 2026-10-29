package cloud.treenewbee.quizforge.content.web;

import cloud.treenewbee.quizforge.content.application.ImportProperties;
import cloud.treenewbee.quizforge.shared.web.ErrorResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Runs after authentication, before DispatcherServlet can resolve multipart parts. */
@Component
@Order(0) // Boot's security chain runs at -100.
public class ImportAdmissionFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final int perMinute;
    private final Clock clock;
    private final JsonMapper json;
    private final Map<String, ArrayDeque<Instant>> attempts = new HashMap<>();

    public ImportAdmissionFilter(ImportProperties properties, Clock clock, JsonMapper json) {
        permits = new Semaphore(properties.maxConcurrency()); perMinute = properties.requestsPerMinute();
        this.clock = clock; this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getServletPath().equals("/api/library/import");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getUserPrincipal() == null) { chain.doFilter(request, response); return; }
        if (!permits.tryAcquire()) { reject(response, 503, "IMPORT_BUSY", "同时导入的任务过多，请稍后重试"); return; }
        try {
            if (!admit(request.getUserPrincipal().getName())) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "RATE_LIMITED", "每分钟最多导入 " + perMinute + " 次，请稍后重试"); return;
            }
            chain.doFilter(request, response);
        } finally { permits.release(); }
    }

    private synchronized boolean admit(String user) {
        Instant now = clock.instant(), cutoff = now.minusSeconds(60);
        attempts.values().forEach(queue -> { while (!queue.isEmpty() && !queue.getFirst().isAfter(cutoff)) queue.removeFirst(); });
        attempts.values().removeIf(ArrayDeque::isEmpty);
        ArrayDeque<Instant> queue = attempts.computeIfAbsent(user, ignored -> new ArrayDeque<>());
        if (queue.size() >= perMinute) return false;
        queue.addLast(now); return true;
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.writeValueAsString(ErrorResponse.of(code, message)));
    }
}
