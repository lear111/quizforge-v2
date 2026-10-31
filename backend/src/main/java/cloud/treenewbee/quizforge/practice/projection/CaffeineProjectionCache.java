package cloud.treenewbee.quizforge.practice.projection;

import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 未提交题目的投影缓存。投影只取决于题目内容和拓展，两者都不可变，
 * 所以缓存永不过期，只按占用内存淘汰；值用不可变的 String，避免多个请求共享可变对象。
 */
@Component
public class CaffeineProjectionCache implements ProjectionCache {

    private final Cache<ProjectionKey, String> cache;

    public CaffeineProjectionCache() { this(new SimpleMeterRegistry()); }

    @Autowired
    public CaffeineProjectionCache(MeterRegistry meters) {
        this.cache = Caffeine.newBuilder()
                .maximumWeight(64L * 1024 * 1024)

                // 按字符数近似占用的内存：条目大小差别很大，按条数限制无法控制内存
                .weigher((ProjectionKey key, String value) -> value.length())

                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meters, cache, "projection");
    }

    @Override
    public String get(ProjectionKey key, Supplier<String> loader) {
        return cache.get(key, k -> loader.get());
    }
}