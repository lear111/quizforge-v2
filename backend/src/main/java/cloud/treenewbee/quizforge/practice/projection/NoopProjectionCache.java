package cloud.treenewbee.quizforge.practice.projection;

import java.util.function.Supplier;

/** Intentionally does not retain or coalesce values; replace this fallback with the production cache. */
public final class NoopProjectionCache implements ProjectionCache {
    @Override public String get(ProjectionKey key, Supplier<String> loader) { return loader.get(); }
}
