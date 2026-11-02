package cloud.treenewbee.quizforge.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.identity.maintenance.MaintenanceCommand;
import cloud.treenewbee.quizforge.identity.verification.FakeVerification;
import cloud.treenewbee.quizforge.identity.verification.VerificationConfig;
import cloud.treenewbee.quizforge.identity.verification.VerificationPurpose;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L4 账号补全的端到端测试：登录与注册设置的每种组合、验证码规则、手机号、找回密码、绑定联系方式、
 * 邀请码、封禁、设置并发修改、管理员与 IDOR、维护命令。验证码由 FakeVerification 记录，不发送真实短信或邮件。
 */
@Import(AccountCompletionIntegrationTest.MutableClockConfiguration.class)
@TestPropertySource(properties = "qf.security.allowed-origin=" + AccountCompletionIntegrationTest.ORIGIN)
class AccountCompletionIntegrationTest extends MySqlTestBase {

    static final String ORIGIN = "https://quizforge-accounts.example.test";
    static final Instant START = Instant.parse("2026-11-01T08:00:00Z");
    private static final String PASSWORD = "pass12345";
    private static final String CAPTCHA = FakeVerification.CAPTCHA_TOKEN;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired VerificationConfig.VerificationServices verification;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private FakeVerification fake;

    @BeforeEach
    void reset() {
        fake = verification.fake();
        fake.reset();
        clock.set(START);
        resetRateLimits();
        jdbc.update("DELETE FROM auth_verification_code");
        setPolicy(false, false, false, false, false);
    }

    @AfterEach
    void restore() {
        fake.reset();
        setPolicy(false, false, false, false, false);
        http.close();
    }

    // ================================================================ 登录与注册设置的组合

    static Stream<Arguments> loginCombinations() {
        List<Arguments> values = new ArrayList<>();
        for (String channel : List.of("email", "phone")) {
            for (boolean code : List.of(false, true)) {
                for (boolean captcha : List.of(false, true)) values.add(Arguments.of(channel, code, captcha));
            }
        }
        return values.stream();
    }

    @ParameterizedTest(name = "{0} 登录：验证码 {1}，人机验证 {2}")
    @MethodSource("loginCombinations")
    void loginHonoursEveryPolicyCombination(String channel, boolean code, boolean captcha) throws Exception {
        String identifier = channel.equals("email") ? email() : phone();
        String id = registerOk(identifier);
        setPolicy(code, captcha, false, false, false);

        if (!code && !captcha) {
            assertOk(login(identifier, PASSWORD, null, null));
            return;
        }
        if (!code) {
            assertError(login(identifier, PASSWORD, null, null), 400, "CAPTCHA_FAILED");
            assertError(login(identifier, PASSWORD, null, "wrong-token"), 400, "CAPTCHA_FAILED");
            assertOk(login(identifier, PASSWORD, null, CAPTCHA));
            return;
        }
        // 需要验证码：直接登录被拒；发码前先核对密码；需要人机验证时在发码时完成。
        assertError(login(identifier, PASSWORD, null, captcha ? CAPTCHA : null), 400, "VERIFICATION_CODE_INVALID");
        assertError(post("/api/auth/login-code", Map.of("identifier", identifier, "password", "wrong-pass"), null),
                401, "INVALID_CREDENTIALS");
        if (captcha) {
            assertError(post("/api/auth/login-code", Map.of("identifier", identifier, "password", PASSWORD), null),
                    400, "CAPTCHA_FAILED");
        }
        assertThat(fake.sent()).isEmpty();
        sendLoginCode(identifier, captcha);
        String sent = fake.lastCode(normalized(identifier));
        assertThat(fake.sent().getLast().purpose()).isEqualTo(VerificationPurpose.LOGIN);
        HttpResponse<String> response = login(identifier, PASSWORD, sent, null);
        assertOk(response);
        // 登录验证码证明拥有该联系方式：清除“未验证”标记，手机号补记验证时间。
        assertThat(user(response).path("unverifiedContacts").size()).isZero();
        if (channel.equals("phone")) {
            assertThat(jdbc.queryForObject("SELECT verified AND verified_at IS NOT NULL FROM account_user_phone "
                    + "WHERE user_id = ?", Boolean.class, id)).isTrue();
        }
        // 验证码只能使用一次。
        assertError(login(identifier, PASSWORD, sent, null), 400, "VERIFICATION_CODE_INVALID");
    }

    static Stream<Arguments> registrationCombinations() {
        List<Arguments> values = new ArrayList<>();
        for (String channel : List.of("email", "phone")) {
            for (boolean code : List.of(false, true)) {
                for (boolean captcha : List.of(false, true)) {
                    for (boolean invitation : List.of(false, true)) {
                        values.add(Arguments.of(channel, code, captcha, invitation));
                    }
                }
            }
        }
        return values.stream();
    }

    @ParameterizedTest(name = "{0} 注册：验证码 {1}，人机验证 {2}，邀请码 {3}")
    @MethodSource("registrationCombinations")
    void registrationHonoursEveryPolicyCombination(String channel, boolean code, boolean captcha,
                                                   boolean invitation) throws Exception {
        String admin = admin();
        setPolicy(false, false, code, captcha, invitation);
        String identifier = channel.equals("email") ? email() : phone();
        String invite = invitation ? createInvitations(admin, 1).getFirst() : null;
        Map<String, Object> body = registration(identifier);

        if (invitation) {
            // 不需要验证码时先检查人机验证，其次检查邀请码。
            assertError(post("/api/auth/register", body, null), 400,
                    captcha && !code ? "CAPTCHA_FAILED" : "INVITATION_INVALID");
            body.put("invitationCode", invite);
        }
        if (code) {
            Map<String, Object> request = new LinkedHashMap<>(Map.of("identifier", identifier));
            if (invitation) request.put("invitationCode", invite);
            String path = channel.equals("email") ? "/api/auth/email-code" : "/api/auth/phone-code";
            if (captcha) assertError(post(path, request, null), 400, "CAPTCHA_FAILED");
            if (captcha) request.put("captchaToken", CAPTCHA);
            assertThat(post(path, request, null).statusCode()).isEqualTo(200);
            assertThat(fake.sent().getLast().purpose()).isEqualTo(VerificationPurpose.REGISTER);
            // 已发码但未填写：计为一次错误。
            assertError(post("/api/auth/register", body, null), 400, "VERIFICATION_CODE_MISMATCH");
            body.put("code", fake.lastCode(normalized(identifier)));
        } else if (captcha) {
            assertError(post("/api/auth/register", body, null), 400, "CAPTCHA_FAILED");
            body.put("captchaToken", CAPTCHA);
        }

        HttpResponse<String> response = post("/api/auth/register", body, null);
        assertOk(response);
        JsonNode user = user(response);
        // 关闭注册验证码时联系方式标为未验证；开启时视为已验证。
        assertThat(user.path("unverifiedContacts").size()).isEqualTo(code ? 0 : 1);
        if (invitation) {
            assertThat(jdbc.queryForObject("SELECT user_id FROM account_invitation WHERE prefix = ?", String.class,
                    invite.substring(0, 11))).isEqualTo(user.path("id").asString());
        }
    }

    @Test
    void publicConfigReportsCurrentRequirements() throws Exception {
        setPolicy(true, false, true, true, true);
        JsonNode config = JSON.readTree(get("/api/config", null).body());
        assertThat(config.path("authPolicy").path("loginCodeRequired").asBoolean()).isTrue();
        assertThat(config.path("authPolicy").path("loginCaptchaRequired").asBoolean()).isFalse();
        assertThat(config.path("authPolicy").path("registrationCaptchaRequired").asBoolean()).isTrue();
        assertThat(config.path("invitationRequired").asBoolean()).isTrue();
        assertThat(config.path("captchaProvider").asString()).isEqualTo("fake");
        assertThat(config.path("verificationMethods").path("phone").asBoolean()).isTrue();
        assertThat(config.toString()).doesNotContain("pepper").doesNotContain("secret");

        fake.setAvailable(cloud.treenewbee.quizforge.identity.domain.Contact.Channel.PHONE, false);
        config = JSON.readTree(get("/api/config", null).body());
        assertThat(config.path("registrationMethods").path("phone").asBoolean()).isFalse();
        assertThat(config.path("registrationMethods").path("email").asBoolean()).isTrue();
    }

    @Test
    void strengthenedPolicyAfterCodeWasSentIsEnforcedAtFinalWrite() throws Exception {
        setPolicy(false, false, true, false, false);
        String identifier = email();
        assertThat(post("/api/auth/email-code", Map.of("identifier", identifier), null).statusCode()).isEqualTo(200);
        String code = fake.lastCode(identifier);
        // 发码时没有经过人机验证；管理员随后开启注册人机验证，旧验证码不能满足新要求。
        setPolicy(false, false, true, true, false);
        Map<String, Object> body = registration(identifier);
        body.put("code", code);
        assertError(post("/api/auth/register", body, null), 409, "POLICY_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_user WHERE email = ?", Long.class, identifier))
                .isZero();
    }

    // ================================================================ 验证码规则

    @Test
    void codesAreIsolatedByPurposeAndBoundToPasswordVersion() throws Exception {
        String identifier = email();
        registerOk(identifier);
        setPolicy(true, false, true, false, false);

        // 注册用途的验证码不能用于登录。
        String other = email();
        post("/api/auth/email-code", Map.of("identifier", other), null);
        resetRateLimits();
        sendLoginCode(identifier, false);
        String loginCode = fake.lastCode(identifier);
        resetRateLimits();
        assertThat(post("/api/auth/password-reset-code", Map.of("identifier", identifier, "captchaToken", CAPTCHA),
                null).statusCode()).isEqualTo(200);
        String resetCode = fake.lastCode(identifier);
        assertError(login(identifier, PASSWORD, resetCode, null), 400, "VERIFICATION_CODE_MISMATCH");

        // 找回密码后，改密前发出的登录验证码失效（绑定了密码版本）。
        assertThat(post("/api/auth/password-reset", Map.of("identifier", identifier, "code", resetCode,
                "password", "new-pass-123"), null).statusCode()).isEqualTo(204);
        assertError(login(identifier, "new-pass-123", loginCode, null), 400, "VERIFICATION_CODE_INVALID");
    }

    @Test
    void codesExpireAfterTenMinutesAndLockAfterFiveWrongAttempts() throws Exception {
        setPolicy(false, false, true, false, false);
        String identifier = email();
        post("/api/auth/email-code", Map.of("identifier", identifier), null);
        String code = fake.lastCode(identifier);
        clock.advance(Duration.ofMinutes(10));
        Map<String, Object> body = registration(identifier);
        body.put("code", code);
        assertError(post("/api/auth/register", body, null), 400, "VERIFICATION_CODE_INVALID");

        // 重发替换旧码并清零错误次数。
        clock.advance(Duration.ofMinutes(1));
        post("/api/auth/email-code", Map.of("identifier", identifier), null);
        String fresh = fake.lastCode(identifier);
        String wrong = fresh.equals("000000") ? "111111" : "000000";
        body.put("code", wrong);
        for (int i = 0; i < 5; i++) {
            assertError(post("/api/auth/register", body, null), 400, "VERIFICATION_CODE_MISMATCH");
        }
        body.put("code", fresh);
        assertError(post("/api/auth/register", body, null), 400, "VERIFICATION_CODE_INVALID");

        clock.advance(Duration.ofMinutes(1));
        post("/api/auth/email-code", Map.of("identifier", identifier), null);
        String replacement = fake.lastCode(identifier);
        body.put("code", code);
        if (!code.equals(replacement)) {
            assertError(post("/api/auth/register", body, null), 400, "VERIFICATION_CODE_MISMATCH");
        }
        body.put("code", replacement);
        assertOk(post("/api/auth/register", body, null));
    }

    @Test
    void sendingIsRateLimitedPerAddressPerIpAndPerDay() throws Exception {
        setPolicy(false, false, true, false, false);
        String identifier = email();
        assertThat(sendRegistrationCode(identifier).statusCode()).isEqualTo(200);
        assertError(sendRegistrationCode(identifier), 429, "VERIFICATION_RATE_LIMITED");
        clock.advance(Duration.ofSeconds(61));
        assertThat(sendRegistrationCode(identifier).statusCode()).isEqualTo(200);

        // 同一地址 24 小时最多 5 次（每次间隔 11 分钟，避开同一 IP 10 分钟 5 次的限制）。
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(11));
            assertThat(sendRegistrationCode(identifier).statusCode()).isEqualTo(200);
        }
        clock.advance(Duration.ofMinutes(11));
        assertError(sendRegistrationCode(identifier), 429, "VERIFICATION_RATE_LIMITED");

        // 同一 IP 10 分钟最多 5 次。
        resetRateLimits();
        for (int i = 0; i < 5; i++) assertThat(sendRegistrationCode(email()).statusCode()).isEqualTo(200);
        assertError(sendRegistrationCode(email()), 429, "VERIFICATION_RATE_LIMITED");

        // 全站每通道每日上限：超出后发送预留不退还。
        resetRateLimits();
        for (int i = 0; i < 100; i++) {
            jdbc.update("INSERT INTO auth_verification_send VALUES (UUID(), 'email', ?, ?, '2026-11-01 08:00:00')",
                    String.format("%064d", i), String.format("%064d", i));
        }
        clock.advance(Duration.ofHours(1));
        assertError(sendRegistrationCode(email()), 429, "VERIFICATION_DAILY_LIMIT");
    }

    @Test
    void concurrentSendsForOneAddressReserveOnlyOnce() throws Exception {
        setPolicy(false, false, true, false, false);
        String identifier = email();
        List<Integer> statuses = concurrently(4, () -> sendRegistrationCode(identifier).statusCode());
        assertThat(statuses).containsOnly(200, 429);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
    }

    @Test
    void failedDeliveryIsReportedAndCodeCannotBeUsed() throws Exception {
        setPolicy(false, false, true, false, false);
        String identifier = email();
        fake.failNext(identifier);
        assertError(sendRegistrationCode(identifier), 503, "VERIFICATION_UNAVAILABLE");
        Map<String, Object> body = registration(identifier);
        body.put("code", "123456");
        assertError(post("/api/auth/register", body, null), 400, "VERIFICATION_CODE_INVALID");
    }

    @Test
    void registrationCodeForTakenContactIsNotDeliveredButResponseIsIdentical() throws Exception {
        String identifier = email();
        registerOk(identifier);
        setPolicy(false, false, true, false, false);
        HttpResponse<String> taken = sendRegistrationCode(identifier);
        HttpResponse<String> fresh = sendRegistrationCode(email());
        assertThat(taken.statusCode()).isEqualTo(200);
        assertThat(taken.body()).isEqualTo(fresh.body());
        assertThat(fake.lastCode(identifier)).isNull();
    }

    // ================================================================ 手机号

    @Test
    void phoneAccountsLogInWithAnyAcceptedSpelling() throws Exception {
        String digits = "139" + String.format("%08d", SEQUENCE.incrementAndGet() + 20_000_000);
        registerOk(digits);
        for (String spelling : List.of(digits.substring(0, 3) + " " + digits.substring(3, 7) + " " + digits.substring(7),
                "+86" + digits, "86" + digits, digits.substring(0, 3) + "-" + digits.substring(3))) {
            JsonNode user = user(assertOk(login(spelling, PASSWORD, null, null)));
            assertThat(user.path("phone").asString()).isEqualTo("+86" + digits);
            assertThat(user.path("email").isNull()).isTrue();
        }
        assertError(post("/api/auth/register", registration("+86" + digits), null), 409, "PHONE_TAKEN");
        assertError(post("/api/auth/login", Map.of("phone", "12345678901", "password", PASSWORD), null),
                400, "VALIDATION_FAILED");
    }

    // ================================================================ 找回密码

    @Test
    void passwordResetRequiresCaptchaRevokesSessionsAndHidesUnknownAccounts() throws Exception {
        String identifier = phone();
        String cookie = cookie(post("/api/auth/register", registration(identifier), null));
        assertError(post("/api/auth/password-reset-code", Map.of("identifier", identifier), null), 400,
                "CAPTCHA_FAILED");
        HttpResponse<String> known = post("/api/auth/password-reset-code",
                Map.of("identifier", identifier, "captchaToken", CAPTCHA), null);
        HttpResponse<String> unknown = post("/api/auth/password-reset-code",
                Map.of("identifier", phone(), "captchaToken", CAPTCHA), null);
        assertThat(known.body()).isEqualTo(unknown.body());
        assertThat(fake.sent()).hasSize(1);

        String code = fake.lastCode(normalized(identifier));
        assertError(post("/api/auth/password-reset", Map.of("identifier", identifier, "code", code,
                "password", "short"), null), 400, "VALIDATION_FAILED");
        assertThat(post("/api/auth/password-reset", Map.of("identifier", identifier, "code", code,
                "password", "brand-new-pass"), null).statusCode()).isEqualTo(204);
        assertThat(get("/api/me", cookie).statusCode()).isEqualTo(401);
        assertError(login(identifier, PASSWORD, null, null), 401, "INVALID_CREDENTIALS");
        assertOk(login(identifier, "brand-new-pass", null, null));
    }

    // ================================================================ 绑定与更换联系方式

    @Test
    void bindingAMissingContactNeedsPasswordCaptchaAndCode() throws Exception {
        String email = email();
        String cookie = cookie(post("/api/auth/register", registration(email), null));
        String phone = phone();
        Map<String, Object> request = new LinkedHashMap<>(Map.of("identifier", phone, "password", "wrong-pass",
                "captchaToken", CAPTCHA));
        assertError(post("/api/account/contact-code", request, cookie), 401, "WRONG_PASSWORD");
        request.put("password", PASSWORD);
        request.remove("captchaToken");
        assertError(post("/api/account/contact-code", request, cookie), 400, "CAPTCHA_FAILED");
        request.put("captchaToken", CAPTCHA);
        assertThat(post("/api/account/contact-code", request, cookie).statusCode()).isEqualTo(200);
        assertThat(fake.sent().getLast().purpose()).isEqualTo(VerificationPurpose.BIND);
        request.remove("captchaToken");
        request.put("code", fake.lastCode(normalized(phone)));
        HttpResponse<String> boundResponse = post("/api/account/contact", request, cookie);
        assertOk200(boundResponse);
        assertThat(user(boundResponse).path("phone").asString()).isEqualTo(normalized(phone));
        assertOk(login(phone, PASSWORD, null, null));
        assertOk(login(email, PASSWORD, null, null));
    }

    @Test
    void boundContactsCannotBeChanged() throws Exception {
        // 已有邮箱的账号不能再绑定（更换）邮箱；补绑手机号后也不能更换手机号（作者决定 D-2，与旧版一致）。
        String email = email();
        String cookie = cookie(post("/api/auth/register", registration(email), null));
        Map<String, Object> change = new LinkedHashMap<>(Map.of("identifier", email(), "password", PASSWORD,
                "captchaToken", CAPTCHA));
        assertError(post("/api/account/contact-code", change, cookie), 409, "CONTACT_ALREADY_BOUND");
        assertThat(JSON.readTree(post("/api/account/contact-code", change, cookie).body()).path("error")
                .path("message").asString()).isEqualTo("此类联系方式已绑定，暂不支持换绑");

        String phone = phone();
        String phoneCookie = cookie(post("/api/auth/register", registration(phone), null));
        Map<String, Object> other = new LinkedHashMap<>(Map.of("identifier", phone(), "password", PASSWORD,
                "captchaToken", CAPTCHA));
        assertError(post("/api/account/contact-code", other, phoneCookie), 409, "CONTACT_ALREADY_BOUND");
        assertThat(fake.sent()).isEmpty();

        // 发码后、确认前账号已经绑定了同类联系方式：确认时仍被拒绝，不覆盖。
        Map<String, Object> bind = new LinkedHashMap<>(Map.of("identifier", email, "password", PASSWORD,
                "captchaToken", CAPTCHA));
        String phoneOnlyId = user(get("/api/me", phoneCookie)).path("id").asString();
        String target = email();
        bind.put("identifier", target);
        assertThat(post("/api/account/contact-code", bind, phoneCookie).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE account_user SET email = ? WHERE id = ?", email(), phoneOnlyId);
        bind.remove("captchaToken");
        bind.put("code", fake.lastCode(target));
        assertError(post("/api/account/contact", bind, phoneCookie), 409, "CONTACT_ALREADY_BOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_user WHERE email = ?", Long.class, target))
                .isZero();
    }

    @Test
    void contactsOwnedByAnotherAccountCannotBeTakenAndOtherAccountsAreUntouched() throws Exception {
        String victim = phone();
        String victimId = registerOk(victim);
        String attacker = email();
        String cookie = cookie(post("/api/auth/register", registration(attacker), null));
        Map<String, Object> request = new LinkedHashMap<>(Map.of("identifier", victim, "password", PASSWORD,
                "captchaToken", CAPTCHA, "userId", victimId));
        int status = post("/api/account/contact-code", request, cookie).statusCode();
        assertThat(status).isIn(400, 409);
        assertThat(fake.sent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT user_id FROM account_user_phone WHERE phone = ?", String.class,
                normalized(victim))).isEqualTo(victimId);
        // 未登录不能调用绑定接口。
        assertThat(post("/api/account/contact-code", request, null).statusCode()).isEqualTo(401);
    }

    // ================================================================ 封禁

    @Test
    void blockedUsersCannotLogInAndExistingSessionsEndImmediately() throws Exception {
        String admin = admin();
        String identifier = email();
        HttpResponse<String> registered = post("/api/auth/register", registration(identifier), null);
        String cookie = cookie(registered);
        String id = user(registered).path("id").asString();
        assertThat(get("/api/me", cookie).statusCode()).isEqualTo(200);

        assertThat(put("/api/admin/users/" + id + "/block", Map.of("blocked", true, "reason", "测试"), admin)
                .statusCode()).isEqualTo(200);
        assertThat(get("/api/me", cookie).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id = ?", Long.class, id))
                .isZero();
        assertError(login(identifier, PASSWORD, null, null), 401, "INVALID_CREDENTIALS");
        assertThat(login(identifier, PASSWORD, null, null).body())
                .isEqualTo(login(identifier, "wrong-pass", null, null).body());
        setPolicy(true, false, false, false, false);
        assertError(post("/api/auth/login-code", Map.of("identifier", identifier, "password", PASSWORD), null),
                401, "INVALID_CREDENTIALS");
        setPolicy(false, false, false, false, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target = ? AND action = ?",
                Long.class, id, "user-blocked")).isEqualTo(1);

        assertThat(put("/api/admin/users/" + id + "/block", Map.of("blocked", false), admin).statusCode())
                .isEqualTo(200);
        assertOk(login(identifier, PASSWORD, null, null));
    }

    @Test
    void sessionValidationRejectsBlockedUsersEvenWithoutAdminEndpoint() throws Exception {
        HttpResponse<String> registered = post("/api/auth/register", registration(email()), null);
        String cookie = cookie(registered);
        jdbc.update("INSERT INTO account_block VALUES (?, 'cli', '', '2026-11-01 08:00:00')",
                user(registered).path("id").asString());
        assertThat(get("/api/me", cookie).statusCode()).isEqualTo(401);
    }

    // ================================================================ 管理员接口与 IDOR

    @Test
    void adminEndpointsRejectNonAdminsAndAnonymousUsers() throws Exception {
        String admin = admin();
        String adminId = user(get("/api/me", admin)).path("id").asString();
        HttpResponse<String> registered = post("/api/auth/register", registration(email()), null);
        String member = cookie(registered);
        String memberId = user(registered).path("id").asString();

        for (String cookie : new String[] {member, null}) {
            int expected = cookie == null ? 401 : 403;
            assertThat(get("/api/admin/authentication", cookie).statusCode()).isEqualTo(expected);
            assertThat(put("/api/admin/authentication", Map.of("loginCodeRequired", true, "revision", 0), cookie)
                    .statusCode()).isEqualTo(expected);
            assertThat(get("/api/admin/invitations", cookie).statusCode()).isEqualTo(expected);
            assertThat(post("/api/admin/invitations", Map.of("count", 1), cookie).statusCode()).isEqualTo(expected);
            assertThat(put("/api/admin/users/" + adminId + "/block", Map.of("blocked", true), cookie).statusCode())
                    .isEqualTo(expected);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_block WHERE user_id = ?", Long.class, adminId))
                .isZero();
        // 管理员不能封禁自己；被封禁的管理员失去管理权限（会话也随之失效）。
        assertError(put("/api/admin/users/" + adminId + "/block", Map.of("blocked", true), admin), 400,
                "VALIDATION_FAILED");
        assertThat(put("/api/admin/users/" + memberId + "/block", Map.of("blocked", false), admin).statusCode())
                .isEqualTo(200);
        assertThat(put("/api/admin/users/00000000-0000-0000-0000-000000000000/block", Map.of("blocked", true),
                admin).statusCode()).isEqualTo(404);
    }

    @Test
    void policyUpdatesUseRevisionAuditAndRejectUnconfiguredServices() throws Exception {
        String first = admin();
        String second = admin();
        long revision = JSON.readTree(get("/api/admin/authentication", first).body()).path("policy")
                .path("revision").asLong();
        long audits = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action = ?", Long.class,
                "authentication-policy-updated");

        assertOk200(put("/api/admin/authentication", Map.of("loginCaptchaRequired", true, "revision", revision),
                second));
        // 另一位管理员仍持有旧版本，保存被拒绝，不覆盖对方的修改。
        assertError(put("/api/admin/authentication", Map.of("registrationCodeRequired", true, "revision", revision),
                first), 409, "POLICY_CONFLICT");
        JsonNode policy = JSON.readTree(get("/api/admin/authentication", first).body()).path("policy");
        assertThat(policy.path("loginCaptchaRequired").asBoolean()).isTrue();
        assertThat(policy.path("registrationCodeRequired").asBoolean()).isFalse();
        assertThat(policy.path("revision").asLong()).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action = ?", Long.class,
                "authentication-policy-updated")).isEqualTo(audits + 1);

        // 缺少 revision、或启用尚未配置的服务都被拒绝。
        assertThat(put("/api/admin/authentication", Map.of("loginCodeRequired", true), first).statusCode())
                .isEqualTo(400);
        fake.setCaptchaReady(false);
        assertError(put("/api/admin/authentication", Map.of("registrationCaptchaRequired", true,
                "revision", revision + 1), first), 400, "VERIFICATION_NOT_CONFIGURED");
        // 已有手机号账号而短信不可用时，不能开启登录验证码（否则会锁住这些用户）。
        registerOk(phone());
        fake.setAvailable(cloud.treenewbee.quizforge.identity.domain.Contact.Channel.PHONE, false);
        assertError(put("/api/admin/authentication", Map.of("loginCodeRequired", true, "revision", revision + 1),
                first), 400, "VERIFICATION_NOT_CONFIGURED");
    }

    @Test
    void concurrentPolicyUpdatesWithTheSameRevisionSaveOnce() throws Exception {
        String admin = admin();
        long revision = JSON.readTree(get("/api/admin/authentication", admin).body()).path("policy")
                .path("revision").asLong();
        List<Integer> statuses = concurrently(4, () -> put("/api/admin/authentication",
                Map.of("invitationRequired", true, "revision", revision), admin).statusCode());
        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
    }

    // ================================================================ 邀请码

    @Test
    void invitationsAreSingleUseRevocableAndSurviveTurningTheSwitchOff() throws Exception {
        String admin = admin();
        setPolicy(false, false, false, false, true);
        List<String> codes = createInvitations(admin, 3, 1);
        assertThat(codes).allMatch(code -> code.matches("QF-(?:[A-F0-9]{8}-){3}[A-F0-9]{8}"));
        JsonNode list = JSON.readTree(get("/api/admin/invitations", admin).body());
        assertThat(list.toString()).doesNotContain(codes.getFirst());

        Map<String, Object> body = registration(email());
        body.put("invitationCode", codes.getFirst().toLowerCase());
        assertOk(post("/api/auth/register", body, null));
        Map<String, Object> reuse = registration(email());
        reuse.put("invitationCode", codes.getFirst());
        assertError(post("/api/auth/register", reuse, null), 400, "INVITATION_INVALID");

        String usedId = invitationId(admin, codes.getFirst());
        assertError(post("/api/admin/invitations/" + usedId + "/revoke", Map.of(), admin), 409, "INVITATION_USED");
        String revokedId = invitationId(admin, codes.get(1));
        assertThat(post("/api/admin/invitations/" + revokedId + "/revoke", Map.of(), admin).statusCode())
                .isEqualTo(200);
        reuse.put("invitationCode", codes.get(1));
        assertError(post("/api/auth/register", reuse, null), 400, "INVITATION_INVALID");

        // 关闭邀请码要求不删除、不消费已生成的码。
        setPolicy(false, false, false, false, false);
        JsonNode after = JSON.readTree(get("/api/admin/invitations", admin).body()).path("items");
        assertThat(states(after, codes)).containsExactly("used", "revoked", "available");

        // 有效期（1 天）过后状态为 expired。
        clock.advance(Duration.ofDays(2));
        assertThat(states(JSON.readTree(get("/api/admin/invitations", admin).body()).path("items"), codes))
                .containsExactly("used", "revoked", "expired");
    }

    @Test
    void oneInvitationRegistersOnlyOneAccountUnderConcurrency() throws Exception {
        String admin = admin();
        setPolicy(false, false, false, false, true);
        String code = createInvitations(admin, 1).getFirst();
        // 密码哈希超过并发数时排队等待（D-12），4 个并发注册都会进入最终事务。
        List<Integer> statuses = concurrently(4, () -> {
            Map<String, Object> body = registration(email());
            body.put("invitationCode", code);
            return post("/api/auth/register", body, null).statusCode();
        });
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses).containsOnly(200, 400);
    }

    @Test
    void concurrentLoginsQueueForPasswordHashingInsteadOfFailing() throws Exception {
        String identifier = email();
        registerOk(identifier);
        // 6 个并发登录、哈希并发数为 2：超出的请求排队等待，全部成功，不返回 503。
        List<Integer> statuses = concurrently(6, () -> login(identifier, PASSWORD, null, null).statusCode());
        assertThat(statuses).containsOnly(200);
    }

    // ================================================================ 服务器维护命令

    @Test
    void maintenanceCommandPromotesAndRecoversLoginVerification() throws Exception {
        String identifier = phone();
        String id = registerOk(identifier);
        setPolicy(true, true, true, true, false);

        assertThat(maintenance("auth-login-recovery", identifier).exit()).isEqualTo(1);
        Maintenance promote = maintenance("promote", "86" + identifier.replace(" ", ""));
        assertThat(promote.exit()).as(promote.err()).isEqualTo(0);
        assertThat(JSON.readTree(promote.out()).path("userId").asString()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT is_admin FROM account_user WHERE id = ?", Boolean.class, id)).isTrue();

        Maintenance recovery = maintenance("auth-login-recovery", identifier);
        assertThat(recovery.exit()).as(recovery.err()).isEqualTo(0);
        var row = jdbc.queryForMap("SELECT login_code, login_captcha, registration_code, registration_captcha "
                + "FROM auth_policy WHERE id = 1");
        assertThat(row.values()).containsExactly(false, false, true, true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action = ? AND actor_id = ?",
                Long.class, "authentication-login-recovery", id)).isEqualTo(1);
        assertThat(maintenance("promote", "nobody@example.test").exit()).isEqualTo(1);
        assertThat(maintenance("unknown", identifier).exit()).isEqualTo(2);
    }

    // ================================================================ 辅助方法

    private record Maintenance(int exit, String out, String err) {
    }

    private Maintenance maintenance(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = MaintenanceCommand.run(args, Map.of(
                "spring.datasource.url", MYSQL.getJdbcUrl(),
                "spring.datasource.username", MYSQL.getUsername(),
                "spring.datasource.password", MYSQL.getPassword(),
                "qf.verification.mode", "fake",
                "qf.security.allowed-origin", ORIGIN),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Maintenance(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void setPolicy(boolean loginCode, boolean loginCaptcha, boolean registrationCode,
                           boolean registrationCaptcha, boolean invitation) {
        jdbc.update("""
                UPDATE auth_policy SET login_code = ?, login_captcha = ?, registration_code = ?,
                       registration_captcha = ?, invitation_required = ? WHERE id = 1
                """, loginCode, loginCaptcha, registrationCode, registrationCaptcha, invitation);
    }

    private void resetRateLimits() {
        jdbc.update("DELETE FROM auth_verification_send");
    }

    private String admin() throws Exception {
        HttpResponse<String> response = post("/api/auth/register", registration(email()), null);
        jdbc.update("UPDATE account_user SET is_admin = TRUE WHERE id = ?", user(response).path("id").asString());
        return cookie(response);
    }

    private String registerOk(String identifier) throws Exception {
        return user(assertOk(post("/api/auth/register", registration(identifier), null))).path("id").asString();
    }

    private Map<String, Object> registration(String identifier) {
        return new LinkedHashMap<>(Map.of("identifier", identifier, "name", "Alice", "password", PASSWORD));
    }

    private List<String> createInvitations(String admin, int count) throws Exception {
        return createInvitations(admin, count, 30);
    }

    private List<String> createInvitations(String admin, int count, int days) throws Exception {
        HttpResponse<String> response = post("/api/admin/invitations", Map.of("count", count, "days", days), admin);
        assertThat(response.statusCode()).isEqualTo(201);
        List<String> codes = new ArrayList<>();
        JSON.readTree(response.body()).path("items").forEach(item -> codes.add(item.path("code").asString()));
        return codes;
    }

    private String invitationId(String admin, String code) throws Exception {
        for (JsonNode item : JSON.readTree(get("/api/admin/invitations", admin).body()).path("items")) {
            if (item.path("prefix").asString().equals(code.substring(0, 11))) return item.path("id").asString();
        }
        throw new AssertionError("invitation not listed");
    }

    private static List<String> states(JsonNode items, List<String> codes) {
        List<String> states = new ArrayList<>();
        for (String code : codes) {
            for (JsonNode item : items) {
                if (item.path("prefix").asString().equals(code.substring(0, 11))) states.add(item.path("state").asString());
            }
        }
        return states;
    }

    private void sendLoginCode(String identifier, boolean captcha) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("identifier", identifier, "password", PASSWORD));
        if (captcha) body.put("captchaToken", CAPTCHA);
        assertThat(post("/api/auth/login-code", body, null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> sendRegistrationCode(String email) throws Exception {
        return post("/api/auth/email-code", Map.of("identifier", email), null);
    }

    private HttpResponse<String> login(String identifier, String password, String code, String captcha)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("identifier", identifier, "password", password));
        if (code != null) body.put("code", code);
        if (captcha != null) body.put("captchaToken", captcha);
        return post("/api/auth/login", body, null);
    }

    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String email() {
        return "acct-" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime() + "@example.test";
    }

    /** 每次生成不同的号码（写法带空格，验证规范化）。 */
    private static String phone() {
        long value = (System.nanoTime() / 1000 + SEQUENCE.incrementAndGet()) % 100_000_000L;
        String digits = "137" + String.format("%08d", value);
        return digits.substring(0, 3) + " " + digits.substring(3, 7) + " " + digits.substring(7);
    }

    private static String normalized(String identifier) {
        return identifier.contains("@") ? identifier : "+86" + identifier.replace(" ", "").replace("-", "");
    }

    private static HttpResponse<String> assertOk(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isPresent();
        return response;
    }

    private static void assertOk200(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        if (response == null) return;
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo(code);
    }

    private static JsonNode user(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("user");
    }

    private static String cookie(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return send("GET", path, null, cookie);
    }

    private HttpResponse<String> post(String path, Object body, String cookie) throws Exception {
        return send("POST", path, JSON.writeValueAsString(body), cookie);
    }

    private HttpResponse<String> put(String path, Object body, String cookie) throws Exception {
        return send("PUT", path, JSON.writeValueAsString(body), cookie);
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Origin", ORIGIN);
        if (body != null) request.header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 可推进的时钟：用于验证码过期、发送频率窗口与邀请码有效期。 */
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void set(Instant value) {
            now.set(value);
        }

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
