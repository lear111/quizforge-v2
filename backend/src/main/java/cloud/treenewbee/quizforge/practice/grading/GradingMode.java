package cloud.treenewbee.quizforge.practice.grading;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 回退开关只影响提交与批量判分，不影响草稿和读题投影。 */
@Component
public record GradingMode(boolean browser, Duration timeout) {
    @org.springframework.beans.factory.annotation.Autowired
    public GradingMode(@Value("${qf.grading.mode:browser}") String mode,
            @Value("${qf.grading.browser-timeout:PT2M}") Duration timeout) {
        this(parse(mode), timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("浏览器判分等待时长必须为正数");
    }

    private static boolean parse(String mode) {
        if (!mode.equals("browser") && !mode.equals("server"))
            throw new IllegalArgumentException("qf.grading.mode 必须为 browser 或 server");
        return mode.equals("browser");
    }
}
