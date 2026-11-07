package cloud.treenewbee.quizforge.ops;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 生产配置的启动检查：provider 模式或站点来源不是本机 / .test 时，所有必需配置都要有效，否则拒绝启动。
 * 一次列出全部问题（变量名与原因，不打印值），不必逐个试错；清单见 docs/ops/01-deploy.md 第 5 节。
 *
 * <p>在其他 Bean 创建之前运行（BeanFactoryPostProcessor），所以报告不会被某个 Bean 自己的检查提前打断；
 * 维护命令（promote 等）使用同一配置，也同样检查。
 *
 * <p>包含 #123 安全审查的开发默认凭据检查（原 {@code DeploymentSecrets}）：只有 fake 模式且来源是本机 / .localhost / .test
 * 时才允许仓库中的开发默认 runner 令牌与数据库密码，其余配置一律拒绝空白值与默认值。
 */
@Configuration
public class ProductionConfigCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigCheck.class);
    static final int MIN_DB_PASSWORD = 16;
    static final int MIN_SECRET = 32;
    /** 仓库中出现过的开发默认值与常见的占位写法。 */
    private static final Set<String> DEFAULTS = Set.of("quizforge", "quizforge-root", "quizforge-local-rule-token");
    private static final List<String> PLACEHOLDERS = List.of("change-me", "changeme", "change_me", "example", "your-", "your_", "placeholder",
            "replace-me", "replaceme", "<", ">");

    /** 一项配置的问题：变量名与原因。 */
    public record Problem(String variable, String reason) {
        @Override
        public String toString() {
            return variable + "：" + reason;
        }
    }

    @Bean
    static BeanFactoryPostProcessor productionConfigGuard(Environment environment) {
        return beanFactory -> {
            List<Problem> problems = problems(environment::getProperty);
            if (problems.isEmpty()) return;
            problems.forEach(problem -> log.error("生产配置不完整：{}", problem));
            throw new IllegalStateException("生产配置不完整，拒绝启动（共 " + problems.size() + " 项，见 docs/ops/01-deploy.md 第 5 节）：" + problems);
        };
    }

    /** 本机开发与测试（fake 模式且来源是本机、.localhost 或 .test）不检查。 */
    static boolean productionLike(String mode, String origin) {
        return !"fake".equals(mode) || !localOrTest(origin);
    }

    /** {@code property} 按 Spring 属性名取值（已解析环境变量），不存在时返回 null。 */
    public static List<Problem> problems(Function<String, String> property) {
        String mode = property.apply("qf.verification.mode");
        String origin = property.apply("qf.security.allowed-origin");
        if (!productionLike(mode, origin)) return List.of();

        List<Problem> problems = new ArrayList<>();
        Checks checks = new Checks(property, problems);
        if (!"provider".equals(mode)) problems.add(new Problem("QF_VERIFICATION_MODE", "必须是 provider"));
        if (!httpsOrigin(origin) && !localOrTest(origin)) {
            problems.add(new Problem("QF_PUBLIC_ORIGIN", "必须是 https 地址，不带路径"));
        }
        checks.secret("spring.datasource.password", "QF_DB_PASSWORD", MIN_DB_PASSWORD);
        checks.secret("qf.rule-runner.token", "QF_RULE_RUNNER_TOKEN", MIN_SECRET);
        checks.secret("qf.verification.email-pepper", "QF_EMAIL_CODE_PEPPER", MIN_SECRET);
        checks.secret("qf.verification.sms-pepper", "QF_SMS_CODE_PEPPER", MIN_SECRET);
        checks.masterKey("qf.ai.master-key", "QF_AI_MASTER_KEY");
        checks.enabled("qf.verification.tencent.enabled", "QF_TENCENT_SES_ENABLED");
        checks.value("qf.verification.tencent.secret-id", "QF_TENCENT_SECRET_ID");
        checks.value("qf.verification.tencent.secret-key", "QF_TENCENT_SECRET_KEY");
        checks.value("qf.verification.tencent.from", "QF_TENCENT_SES_FROM", false);
        checks.value("qf.verification.tencent.template-id", "QF_TENCENT_SES_TEMPLATE_ID");
        checks.enabled("qf.verification.aliyun.enabled", "QF_ALIYUN_SMS_ENABLED");
        checks.value("qf.verification.aliyun.access-key-id", "QF_ALIYUN_SMS_ACCESS_KEY_ID");
        checks.value("qf.verification.aliyun.access-key-secret", "QF_ALIYUN_SMS_ACCESS_KEY_SECRET");
        checks.value("qf.verification.aliyun.sign-name", "QF_ALIYUN_SMS_SIGN_NAME");
        checks.value("qf.verification.aliyun.template-code", "QF_ALIYUN_SMS_TEMPLATE_CODE");
        checks.value("qf.verification.turnstile.site-key", "QF_TURNSTILE_SITE_KEY");
        checks.value("qf.verification.turnstile.secret-key", "QF_TURNSTILE_SECRET_KEY");
        return problems;
    }

    private record Checks(Function<String, String> property, List<Problem> problems) {

        void secret(String name, String variable, int minimum) {
            String value = value(name, variable);
            if (value != null && value.length() < minimum) {
                problems.add(new Problem(variable, "至少 " + minimum + " 个字符"));
            }
        }

        void masterKey(String name, String variable) {
            // 随机 Base64 可能碰巧含有占位写法中的文字，这里只检查格式。
            String value = value(name, variable, false);
            if (value == null) return;
            try {
                if (Base64.getDecoder().decode(value).length != 32) problems.add(new Problem(variable, "必须是 32 字节的 Base64"));
            } catch (IllegalArgumentException error) {
                problems.add(new Problem(variable, "必须是 32 字节的 Base64"));
            }
        }

        void enabled(String name, String variable) {
            if (!"true".equalsIgnoreCase(property.apply(name))) problems.add(new Problem(variable, "必须设为 true"));
        }

        String value(String name, String variable) {
            return value(name, variable, true);
        }

        /** 发件人地址这类值可以含 example 以外的任何文字，但不能缺失；{@code strict} 时再检查占位写法。 */
        String value(String name, String variable, boolean strict) {
            String value = property.apply(name);
            if (value == null || value.isBlank()) {
                problems.add(new Problem(variable, "未设置"));
                return null;
            }
            if (DEFAULTS.contains(value) || placeholder(value, strict)) {
                problems.add(new Problem(variable, "仍是开发默认值或示例值"));
                return null;
            }
            return value;
        }
    }

    private static boolean placeholder(String value, boolean strict) {
        String text = value.toLowerCase(Locale.ROOT);
        if (!strict) return text.contains("example.com") || text.contains("change-me") || text.contains("changeme");
        return PLACEHOLDERS.stream().anyMatch(text::contains);
    }

    private static boolean httpsOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getUserInfo() == null;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean localOrTest(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.endsWith(".localhost") || host.endsWith(".test"));
        } catch (RuntimeException error) {
            return false;
        }
    }
}
