package cloud.treenewbee.quizforge.practice.projection;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.test.util.ReflectionTestUtils;

/** Inspect the actual production cache without adding test hooks to its public API. */
public final class ProjectionCacheTestSupport {
    private ProjectionCacheTestSupport() {}

    @SuppressWarnings("unchecked")
    public static Cache<ProjectionKey, String> backing(ProjectionCache projection) {
        if (!(projection instanceof CaffeineProjectionCache)) throw new AssertionError("Expected production CaffeineProjectionCache");
        Object backing = ReflectionTestUtils.getField(projection, "cache");
        if (!(backing instanceof Cache<?, ?>)) throw new AssertionError("Caffeine backing cache missing");
        return (Cache<ProjectionKey, String>) backing;
    }
}
