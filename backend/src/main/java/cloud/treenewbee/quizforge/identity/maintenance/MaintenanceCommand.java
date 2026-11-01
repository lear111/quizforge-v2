package cloud.treenewbee.quizforge.identity.maintenance;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import cloud.treenewbee.quizforge.QuizForgeApplication;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.identity.application.AuthPolicyService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 服务器维护命令，沿用旧平台的命令行方式，不是公开 HTTP 接口：
 *
 * <pre>
 * java -jar app.jar maintenance promote &lt;邮箱或手机号&gt;
 * java -jar app.jar maintenance demote &lt;邮箱或手机号&gt;
 * java -jar app.jar maintenance auth-login-recovery &lt;已有管理员的邮箱或手机号&gt;
 * </pre>
 *
 * <p>以非 Web 方式启动应用：不监听端口、不执行 Flyway（须与正在运行的应用为同一版本），
 * 使用与应用相同的数据库环境变量。成功时输出一行 JSON 并返回 0，失败时返回 1。
 */
public final class MaintenanceCommand {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MaintenanceCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length > 0 && args[0].equals("maintenance");
    }

    public static void main(String[] args) {
        System.exit(run(Arrays.copyOfRange(args, 1, args.length), Map.of(), System.out, System.err));
    }

    /** 测试可传入额外的 Spring 属性（例如测试数据库地址）。 */
    public static int run(String[] args, Map<String, Object> properties, PrintStream out, PrintStream err) {
        List<String> command = List.of(args);
        if (!valid(command)) {
            err.println("用法：maintenance promote|demote|auth-login-recovery <邮箱或手机号>");
            return 2;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.flyway.enabled", "false");
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("spring.main.lazy-initialization", "true");
        defaults.put("logging.level.root", "WARN");
        defaults.putAll(properties);
        // 以命令行参数传入，优先级高于 application.yml（例如必须覆盖 spring.flyway.enabled=true）。
        String[] overrides = defaults.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(QuizForgeApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(overrides)) {
            Object result = execute(command, context);
            out.println(JSON.writeValueAsString(result));
            return 0;
        } catch (RuntimeException error) {
            Throwable cause = error;
            while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) cause = cause.getCause();
            // 只在运维者的终端输出；业务错误给出原因，其他错误给出根因类型与消息便于排查。
            err.println("维护命令失败：" + (cause instanceof IllegalArgumentException ? cause.getMessage()
                    : cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            return 1;
        }
    }

    private static Object execute(List<String> command, ConfigurableApplicationContext context) {
        var admins = context.getBean(AccountAdminService.class);
        String action = command.getFirst();
        String identifier = command.get(1);
        return switch (action) {
            case "promote", "demote" -> {
                boolean grant = action.equals("promote");
                String userId = admins.setAdmin(identifier, grant);
                yield Map.of("ok", true, "userId", userId, "admin", grant);
            }
            case "auth-login-recovery" -> {
                String actor = admins.requireExistingAdmin(identifier);
                yield Map.of("ok", true, "policy", context.getBean(AuthPolicyService.class).loginRecovery(actor));
            }
            default -> throw new IllegalArgumentException("不支持的维护命令");
        };
    }

    private static boolean valid(List<String> command) {
        return command.size() == 2 && List.of("promote", "demote", "auth-login-recovery").contains(command.getFirst());
    }
}
