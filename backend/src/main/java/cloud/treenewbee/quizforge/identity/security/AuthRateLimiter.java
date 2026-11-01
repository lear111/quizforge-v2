package cloud.treenewbee.quizforge.identity.security;

/**
 * 匿名认证接口的频率限制。当前为单实例内存实现（{@link InMemoryAuthRateLimiter}）；
 * 部署多个实例后换成共享存储（Redis）实现，调用方不变。
 */
public interface AuthRateLimiter {

    /** 一次判断的结果；拒绝时给出建议的重试等待秒数。 */
    record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /** 为 key（客户端 IP）记一次请求；超过上限时不计数并返回拒绝。 */
    Decision tryAcquire(String key);
}
