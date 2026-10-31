package cloud.treenewbee.quizforge;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import cloud.treenewbee.quizforge.practice.projection.*;

class ObservabilityIntegrationTest extends MySqlTestBase {
    @Value("${local.server.port}") int appPort;
    @Value("${local.management.port}") int managementPort;
    @Autowired MeterRegistry meters;
    @Autowired ProjectionCache cache;
    @Test void prometheusIsOnSeparatePortAndExportsHistogramsPoolAndCache() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+appPort+"/api/health")).build(), HttpResponse.BodyHandlers.ofString());
            var key = new ProjectionKey("observability-test", "extension");
            cache.get(key, () -> "{}"); cache.get(key, () -> { throw new AssertionError("cache hit"); });
            var scrape = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+managementPort+"/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(managementPort).isNotEqualTo(appPort);
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("http_server_requests_seconds_bucket", "uri=\"/api/health\"",
                    "hikaricp_connections_active", "qf_rule_runner_bulkhead_active", "cache_gets_total", "cache=\"projection\"");
            assertThat(meters.get("cache.gets").tags("cache","projection","result","hit").functionCounter().count()).isGreaterThanOrEqualTo(1);
            var external = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+appPort+"/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(external.statusCode()).isNotEqualTo(200);
            assertThat(external.body()).doesNotContain("http_server_requests_seconds_bucket");
            var hidden = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+managementPort+"/actuator/env")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(hidden.statusCode()).isBetween(400,499);
        }
    }
}
