package cloud.treenewbee.quizforge.practice.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProjectionConfiguration {
    @Bean
    @ConditionalOnMissingBean(ProjectionCache.class)
    ProjectionCache projectionCache() { return new NoopProjectionCache(); }
}
