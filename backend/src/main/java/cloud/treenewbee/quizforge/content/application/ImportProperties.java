package cloud.treenewbee.quizforge.content.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("qf.import")
public record ImportProperties(@DefaultValue("2") int maxConcurrency, @DefaultValue("5") int requestsPerMinute) {
    public ImportProperties {
        if (maxConcurrency < 1 || requestsPerMinute < 1) throw new IllegalArgumentException("Invalid import capacity");
    }
}
