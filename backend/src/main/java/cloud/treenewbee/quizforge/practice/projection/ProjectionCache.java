package cloud.treenewbee.quizforge.practice.projection;

import java.util.function.Supplier;

public interface ProjectionCache {
    /** 取出投影；未命中时调用 loader 计算并存入；同一个键并发未命中时 loader 只执行一次。返回投影 JSON 字符串。 */
    String get(ProjectionKey key, Supplier<String> loader);
}
