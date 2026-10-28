package cloud.treenewbee.quizforge.shared.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供统一的时钟。业务代码通过注入 Clock 获取当前时间，测试时可换成固定时钟。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}