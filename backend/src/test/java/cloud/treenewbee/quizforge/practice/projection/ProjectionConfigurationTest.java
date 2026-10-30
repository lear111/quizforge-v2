package cloud.treenewbee.quizforge.practice.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProjectionConfigurationTest {
    @Test void fallbackInvokesLoaderEveryTimeAndRetainsNoValue() {
        var key = new ProjectionKey("content", "extension");
        var calls = new AtomicInteger();
        ProjectionCache cache = new NoopProjectionCache();
        assertThat(cache.get(key, () -> "projection-" + calls.incrementAndGet())).isEqualTo("projection-1");
        assertThat(cache.get(key, () -> "projection-" + calls.incrementAndGet())).isEqualTo("projection-2");
        var failure = new IllegalStateException("loader");
        assertThatThrownBy(() -> cache.get(key, () -> { throw failure; })).isSameAs(failure);
    }
    @Test void defaultBeanIsNoop() {
        new ApplicationContextRunner().withUserConfiguration(ProjectionConfiguration.class)
                .run(context -> assertThat(context.getBean(ProjectionCache.class)).isInstanceOf(NoopProjectionCache.class));
    }
    @Test void suppliedImplementationReplacesFallback() {
        ProjectionCache replacement = (key, loader) -> "replacement";
        new ApplicationContextRunner().withBean(ProjectionCache.class, () -> replacement)
                .withUserConfiguration(ProjectionConfiguration.class)
                .run(context -> assertThat(context.getBeansOfType(ProjectionCache.class).values()).containsExactly(replacement));
    }
    @Test void keysDistinguishBothContentAndExtension() {
        var key = new ProjectionKey("content", "extension");
        assertThat(key).isEqualTo(new ProjectionKey("content", "extension"))
                .isNotEqualTo(new ProjectionKey("other", "extension"))
                .isNotEqualTo(new ProjectionKey("content", "other"));
    }
}
