package cloud.treenewbee.quizforge.shared.web;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and database connectivity check used by Docker, Nginx smoke tests and monitoring.
 *
 * <p>Returns 200 {@code {"ok":true,"database":"up"}} when MySQL answers, otherwise 503
 * {@code DATABASE_UNAVAILABLE}.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库暂时不可用");
        }
        return Map.of("ok", true, "database", "up");
    }
}
