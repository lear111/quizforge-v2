package cloud.treenewbee.quizforge.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.MySqlTestBase;

/**
 * V10 把未保存过的初始设置改为上线默认：只要求注册验证码（D-1）。测试基类在每个测试前把设置
 * 重置为全部关闭，因此这里在事务内重放 V10 脚本，验证它对初始行与已保存行的效果。
 */
@Transactional
class DefaultAuthPolicyMigrationTest extends MySqlTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationIsInstalled() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success = 1",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void untouchedPolicyBecomesRegistrationCodeOnly() throws Exception {
        jdbc.update("UPDATE auth_policy SET login_code = TRUE, login_captcha = TRUE, registration_code = FALSE, "
                + "registration_captcha = TRUE, invitation_required = TRUE, revision = 0 WHERE id = 1");
        jdbc.update(script());
        assertThat(policy()).containsExactly(
                Map.entry("login_code", false), Map.entry("login_captcha", false),
                Map.entry("registration_code", true), Map.entry("registration_captcha", false),
                Map.entry("invitation_required", false));
    }

    @Test
    void policySavedByAnAdministratorIsKept() throws Exception {
        jdbc.update("UPDATE auth_policy SET login_code = TRUE, login_captcha = FALSE, registration_code = FALSE, "
                + "registration_captcha = FALSE, invitation_required = TRUE, revision = 3 WHERE id = 1");
        jdbc.update(script());
        assertThat(policy()).containsEntry("login_code", true).containsEntry("registration_code", false)
                .containsEntry("invitation_required", true);
    }

    private Map<String, Object> policy() {
        return jdbc.queryForMap("SELECT login_code, login_captcha, registration_code, registration_captcha, "
                + "invitation_required FROM auth_policy WHERE id = 1");
    }

    private static String script() throws Exception {
        String sql = new ClassPathResource("db/migration/V10__default_auth_policy.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        StringBuilder statement = new StringBuilder();
        for (String line : sql.split("\n")) if (!line.startsWith("--")) statement.append(line).append('\n');
        return statement.toString().trim().replaceAll(";$", "");
    }
}
