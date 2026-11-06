package cloud.treenewbee.quizforge.client.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.client.infrastructure.DeviceMapper;
import cloud.treenewbee.quizforge.client.infrastructure.DeviceMapper.Device;
import cloud.treenewbee.quizforge.client.infrastructure.DeviceMapper.Pending;
import cloud.treenewbee.quizforge.identity.infrastructure.AccountMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 设备连接：授权码 + PKCE 换取设备令牌（遗留契约 C-05 第 1–8 节，规则与旧平台 DeviceService 相同）。
 * 令牌、授权码与授权请求的键在库中只存 SHA-256。旧设备令牌不迁移（FR-09）。
 */
@Service
public class DeviceService {

    /** 确认页需要的内容。 */
    public record Consent(String key, String client, String deviceName) {
    }

    private static final Pattern WEB_REDIRECT = Pattern.compile("^http://(?:127\\.0\\.0\\.1|localhost):([1-9][0-9]{3,4})/hub/connect/callback$");
    private static final String ANDROID_REDIRECT = "quizforge://hub/connect";
    private static final Pattern STATE = Pattern.compile("[A-Za-z0-9._~-]{22,256}");
    private static final Pattern BASE64URL_43 = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Pattern BEARER = Pattern.compile("Bearer (qfd_[A-Za-z0-9_-]{43})");
    private static final Pattern REQUEST_KEY = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> AUTHORIZE_FIELDS = Set.of("v", "client", "redirect_uri", "state", "code_challenge",
            "code_challenge_method", "device_name");
    private static final Set<String> EXCHANGE_FIELDS = Set.of("v", "client", "code", "code_verifier", "redirect_uri");
    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    private static final Duration CODE_TTL = Duration.ofSeconds(120);
    private static final Duration DEVICE_IDLE = Duration.ofDays(180);
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);
    private static final int MAX_PENDING = 5000;
    private static final int MAX_DEVICES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final DeviceMapper devices;
    private final AccountMapper accounts;
    private final ClientSettings settings;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DeviceService(DeviceMapper devices, AccountMapper accounts, ClientSettings settings,
                         TransactionTemplate transactions, Clock clock) {
        this.devices = devices;
        this.accounts = accounts;
        this.settings = settings;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void requireEnabled() {
        if (!settings.connectEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OFFICIAL_AI_DISABLED", "设备连接暂未开放");
        }
    }

    public static ApiException rejected(HttpStatus status, String message) {
        return new ApiException(status, "CONNECT_REQUEST_REJECTED", message);
    }

    // ---------------------------------------------------------------- 授权页

    /** 校验授权请求并保存（10 分钟有效）；登录后带 request 回到页面。参数不符合时不重定向。 */
    public Consent authorize(Map<String, String[]> params) {
        requireEnabled();
        Instant now = clock.instant();
        devices.deleteExpiredRequests(now);
        devices.deleteExpiredCodes(now);
        if (params.size() == 1 && params.containsKey("request")) {
            String[] keys = params.get("request");
            Pending pending = keys.length == 1 && REQUEST_KEY.matcher(keys[0]).matches()
                    ? devices.findRequest(hash(keys[0]), now).orElse(null) : null;
            if (pending == null) throw rejected(HttpStatus.BAD_REQUEST, "连接请求已过期，请回到 QuizForge 重新点击登录");
            return new Consent(keys[0], pending.client(), pending.deviceName());
        }
        if (!params.keySet().equals(AUTHORIZE_FIELDS) || params.values().stream().anyMatch(values -> values.length != 1)) {
            throw invalidRequest();
        }
        String client = params.get("client")[0];
        String redirect = params.get("redirect_uri")[0];
        String state = params.get("state")[0];
        String challenge = params.get("code_challenge")[0];
        if (!"1".equals(params.get("v")[0]) || !redirectAllowed(client, redirect) || !STATE.matcher(state).matches()
                || !BASE64URL_43.matcher(challenge).matches() || !"S256".equals(params.get("code_challenge_method")[0])) {
            throw invalidRequest();
        }
        String name = name(params.get("device_name")[0]);
        if (devices.requestCount() >= MAX_PENDING) throw rejected(HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后再试");
        String key = hex(32);
        changed(devices.insertRequest(hash(key), client, redirect, state, challenge, name, now.plus(REQUEST_TTL)));
        return new Consent(key, client, name);
    }

    private static ApiException invalidRequest() {
        return rejected(HttpStatus.BAD_REQUEST, "连接请求无效，请回到 QuizForge 重新点击登录");
    }

    static boolean redirectAllowed(String client, String uri) {
        if ("android".equals(client)) return ANDROID_REDIRECT.equals(uri);
        if (!"web".equals(client)) return false;
        Matcher match = WEB_REDIRECT.matcher(uri);
        if (!match.matches()) return false;
        int port = Integer.parseInt(match.group(1));
        return port >= 1024 && port <= 65535;
    }

    /** 去除控制字符后 1–40 个字符。 */
    static String name(Object raw) {
        if (!(raw instanceof String text)) throw rejected(HttpStatus.BAD_REQUEST, "设备名称请填写 1–40 个字符");
        String value = text.replaceAll("[\\x00-\\x1f\\x7f-\\x9f]", "").strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 40) {
            throw rejected(HttpStatus.BAD_REQUEST, "设备名称请填写 1–40 个字符");
        }
        return value;
    }

    /** 用户在确认页点击允许或拒绝：返回跳回客户端的地址。授权请求只能决定一次。 */
    public String decide(String userId, Object rawKey, Object rawDecision) {
        requireEnabled();
        Instant now = clock.instant();
        devices.deleteExpiredRequests(now);
        String key = rawKey instanceof String text && REQUEST_KEY.matcher(text).matches() ? text : null;
        Pending pending = key == null ? null : devices.findRequest(hash(key), now).orElse(null);
        if (pending == null) throw rejected(HttpStatus.GONE, "连接请求已过期，请回到 QuizForge 重新点击登录");
        if (!"allow".equals(rawDecision) && !"deny".equals(rawDecision)) throw rejected(HttpStatus.BAD_REQUEST, "请选择允许或拒绝");
        if (devices.deleteRequest(hash(key)) != 1) throw rejected(HttpStatus.GONE, "连接请求已过期，请回到 QuizForge 重新点击登录");
        if ("deny".equals(rawDecision)) return redirect(pending, "error", "access_denied");
        String code = base64Url(32);
        transactions.executeWithoutResult(status -> {
            // 与封禁互斥：封禁事务已提交后不再签发；封禁在后时由它删除授权码。
            accounts.lockUser(userId);
            if (devices.blocked(userId)) throw rejected(HttpStatus.FORBIDDEN, "账号已停用，无法连接设备");
            changed(devices.insertCode(hash(code), userId, pending.client(), pending.redirectUri(), pending.challenge(),
                    pending.deviceName(), clock.instant().plus(CODE_TTL)));
        });
        return redirect(pending, "code", code);
    }

    private static String redirect(Pending pending, String field, String value) {
        return pending.redirectUri() + "?" + field + "=" + encode(value) + "&state=" + encode(pending.state());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------------------------------------------------------------- 换取令牌

    /** 授权码只要存在就在本次请求中作废（失败也不能重试）；任何校验失败都是 400 INVALID_GRANT。 */
    public Map<String, Object> exchange(Map<String, Object> input) {
        requireEnabled();
        if (!input.keySet().equals(EXCHANGE_FIELDS) || !(input.get("v") instanceof Number version) || version.doubleValue() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRANT", "换取设备令牌的请求无效");
        }
        String code = input.get("code") instanceof String text && BASE64URL_43.matcher(text).matches() ? text : null;
        String verifier = input.get("code_verifier") instanceof String text && VERIFIER.matcher(text).matches() ? text : null;
        // 先普通读：不存在的行不加锁（避免间隙锁）；真正的一次性由删除的影响行数保证。
        var grant = code == null ? null : devices.findCode(hash(code)).orElse(null);
        Map<String, Object> result = grant == null ? null : transactions.execute(status -> {
            if (devices.deleteCode(hash(code)) != 1) return null;
            Instant now = clock.instant();
            String expected = verifier == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
            accounts.lockUser(grant.userId());
            if (!grant.expiresAt().isAfter(now) || !Objects.equals(grant.client(), input.get("client"))
                    || !Objects.equals(grant.redirectUri(), input.get("redirect_uri")) || expected == null
                    || !MessageDigest.isEqual(grant.challenge().getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))
                    || devices.blocked(grant.userId())) {
                return null; // 提交事务：授权码已经作废。
            }
            String token = "qfd_" + base64Url(32);
            String id = Ids.uuidV7();
            changed(devices.insertDevice(id, grant.userId(), hash(token), grant.deviceName(), grant.client(), now));
            for (String old : devices.beyondLimit(grant.userId(), MAX_DEVICES)) devices.revoke(old, grant.userId(), now);
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("id", id);
            device.put("name", grant.deviceName());
            device.put("client", grant.client());
            device.put("created", iso(now));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("token", token);
            value.put("device", device);
            value.put("user", Map.of("name", devices.userName(grant.userId())));
            return value;
        });
        if (result == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRANT", "授权已过期或无效，请重新登录");
        return result;
    }

    // ---------------------------------------------------------------- 设备令牌

    /** 只从 Authorization 头认证设备；Cookie 不起作用。令牌无效、已撤销、过久未用或账号被封禁都是 401。 */
    public Device device(String authorization) {
        requireEnabled();
        Matcher match = authorization == null ? null : BEARER.matcher(authorization);
        if (match == null || !match.matches()) throw invalidToken();
        Instant now = clock.instant();
        Device device = devices.active(hash(match.group(1)), now.minus(DEVICE_IDLE)).orElseThrow(DeviceService::invalidToken);
        if (device.lastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) devices.touch(device.id(), device.userId(), now);
        return device;
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "DEVICE_TOKEN_INVALID", "设备登录已失效，请在 AI 设置中重新登录");
    }

    public void logout(Device device) {
        devices.revoke(device.id(), device.userId(), clock.instant());
    }

    // ---------------------------------------------------------------- 账号页（浏览器会话）

    public String userName(String userId) {
        return devices.userName(userId);
    }

    public List<Map<String, Object>> list(String userId) {
        requireEnabled();
        return devices.list(userId, clock.instant().minus(DEVICE_IDLE)).stream().map(device -> view(device, true)).toList();
    }

    public Map<String, Object> rename(String userId, String id, Object rawName) {
        requireEnabled();
        devices.owned(id, userId).orElseThrow(DeviceService::deviceMissing);
        String name = name(rawName);
        if (devices.rename(id, userId, name) == 0) {
            // 名称未变时 MySQL 也可能报告 0 行：确认设备仍然存在。
            devices.owned(id, userId).orElseThrow(DeviceService::deviceMissing);
        }
        return view(devices.owned(id, userId).orElseThrow(DeviceService::deviceMissing), true);
    }

    public void revoke(String userId, String id) {
        requireEnabled();
        if (devices.revoke(id, userId, clock.instant()) != 1) throw deviceMissing();
    }

    private static ApiException deviceMissing() {
        return rejected(HttpStatus.NOT_FOUND, "设备不存在或已撤销");
    }

    public static Map<String, Object> view(Device device, boolean lastUsed) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", device.id());
        value.put("name", device.name());
        value.put("client", device.client());
        value.put("created", iso(device.createdAt()));
        if (lastUsed) value.put("lastUsed", iso(device.lastUsedAt()));
        return value;
    }

    // ---------------------------------------------------------------- 内部

    private static String iso(Instant time) {
        return ISO_MILLIS.format(time);
    }

    private static void changed(int rows) {
        if (rows != 1) throw new IllegalStateException("设备连接未写入");
    }

    private static byte[] hash(String value) {
        return sha256(value);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String base64Url(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String hex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return HexFormat.of().formatHex(data);
    }
}
