package cloud.treenewbee.quizforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class HealthEndpointTest extends MySqlTestBase {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReportsDatabaseUp() throws Exception {
        HttpResponse<String> response = get("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"ok\":true").contains("\"database\":\"up\"");
    }

    @Test
    void unknownApiPathUsesSharedErrorFormat() throws Exception {
        HttpResponse<String> response = get("/api/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"error\"").contains("\"code\":\"NOT_FOUND\"");
    }
}
