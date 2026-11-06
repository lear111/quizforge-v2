package cloud.treenewbee.quizforge.client.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.ai.application.AiBalanceService;
import cloud.treenewbee.quizforge.ai.application.RedeemCodeService;
import cloud.treenewbee.quizforge.ai.application.UpstreamKeyService;
import cloud.treenewbee.quizforge.client.application.ClientRateLimiter;
import cloud.treenewbee.quizforge.client.application.DeviceService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 设备连接接口（遗留契约 C-05 第 1–8 节）。路径、参数、响应与错误码保持不变。
 * /api/connect/{token,me,redeem,logout} 只认设备令牌（不做 Origin 校验、Cookie 无效）；
 * 确认授权与设备列表只认浏览器会话（照常做 Origin 校验，设备令牌无效）。
 */
@RestController
public class ConnectController {

    private final DeviceService devices;
    private final ClientRateLimiter limits;
    private final AiBalanceService balances;
    private final RedeemCodeService codes;
    private final UpstreamKeyService keys;

    public ConnectController(DeviceService devices, ClientRateLimiter limits, AiBalanceService balances,
                             RedeemCodeService codes, UpstreamKeyService keys) {
        this.devices = devices;
        this.limits = limits;
        this.balances = balances;
        this.codes = codes;
        this.keys = keys;
    }

    // ---------------------------------------------------------------- 授权页（浏览器）

    @GetMapping("/connect/authorize")
    public ResponseEntity<String> consent(HttpServletRequest request, Principal user) {
        limit("connect-authorize", request.getRemoteAddr(), 30);
        DeviceService.Consent consent;
        try {
            consent = devices.authorize(request.getParameterMap());
        } catch (ApiException error) {
            return html(error.status(), "<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><title>连接请求无效</title><p>"
                    + escape(error.getMessage()) + "</p></html>");
        }
        if (user == null) {
            String back = "/connect/authorize?request=" + consent.key();
            return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                    .header("Location", "/?returnTo=" + URLEncoder.encode(back, StandardCharsets.UTF_8)).build();
        }
        return html(HttpStatus.OK, page(consent, devices.userName(user.getName())));
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                        + "form-action 'none'; base-uri 'none'; frame-ancestors 'none'")
                .body(body);
    }

    private static String page(DeviceService.Consent consent, String account) {
        String kind = "web".equals(consent.client()) ? "电脑" : "Android";
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>授权登录 · QuizForge</title>"
                + "<link rel=\"stylesheet\" href=\"/connect/consent.css\"><script type=\"module\" src=\"/connect/consent.js\"></script></head>"
                + "<body><main class=\"consent\"><section class=\"consent-card\" data-request=\"" + consent.key() + "\">"
                + "<h1>授权这台设备登录？</h1><dl><dt>设备</dt><dd>" + escape(consent.deviceName()) + "（" + kind + "）</dd><dt>账号</dt><dd>" + escape(account) + "</dd></dl>"
                + "<p>授权后，这台设备将以你的账号登录 QuizForge，可以使用账号的 AI 额度。你可以在账号页撤销设备连接。</p>"
                + "<div class=\"consent-actions\"><button type=\"button\" data-decision=\"allow\">授权登录</button>"
                + "<button type=\"button\" class=\"secondary\" data-decision=\"deny\">取消</button></div>"
                + "<p class=\"consent-error\" role=\"alert\"></p></section></main></body></html>";
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    @PostMapping("/api/connect/authorize")
    public Map<String, String> decide(HttpServletRequest request, Principal user) {
        devices.requireEnabled();
        String userId = sessionUser(user);
        limit("connect-decision", userId, 20);
        Map<String, Object> body = ClientJson.body(request);
        return Map.of("redirect", devices.decide(userId, body.get("request"), body.get("decision")));
    }

    // ---------------------------------------------------------------- 设备令牌

    @PostMapping("/api/connect/token")
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
        devices.requireEnabled();
        limit("connect-token", request.getRemoteAddr(), 20);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(devices.exchange(ClientJson.body(request)));
    }

    @GetMapping("/api/connect/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        var device = devices.device(request.getHeader("Authorization"));
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("enabled", !keys.candidates().isEmpty());
        ai.put("model", "QuizForge 官方模型");
        ai.put("vision", true);
        // 旧客户端的余额字段是非负整数；V2 允许欠费，这里按 0 显示。
        ai.put("balance", Math.max(0, balances.balance(device.userId()).balance()));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("user", Map.of("name", device.userName()));
        value.put("device", DeviceService.view(device, false));
        value.put("ai", ai);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    @PostMapping("/api/connect/redeem")
    public Map<String, Long> redeem(HttpServletRequest request) {
        var device = devices.device(request.getHeader("Authorization"));
        limit("ai-redeem", device.userId(), 10);
        Object raw = ClientJson.body(request).get("code");
        // 与网页共用兑换码服务：同样的格式规范化、单次兑换与 1 小时输错 10 次锁定。
        var result = codes.redeem(device.userId(), raw instanceof String text ? text : null);
        return Map.of("added", result.added(), "balance", Math.max(0, result.balance()));
    }

    @PostMapping("/api/connect/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        devices.logout(devices.device(request.getHeader("Authorization")));
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- 账号页（浏览器会话）

    @GetMapping("/api/connect/devices")
    public ResponseEntity<Map<String, Object>> list(Principal user) {
        devices.requireEnabled();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("devices", devices.list(sessionUser(user))));
    }

    @PutMapping("/api/connect/devices/{id}")
    public Map<String, Object> rename(HttpServletRequest request, Principal user, @PathVariable String id) {
        devices.requireEnabled();
        String userId = sessionUser(user);
        limit("connect-devices", userId, 30);
        return Map.of("device", devices.rename(userId, id, ClientJson.body(request).get("name")));
    }

    @DeleteMapping("/api/connect/devices/{id}")
    public Map<String, Boolean> revoke(Principal user, @PathVariable String id) {
        devices.requireEnabled();
        String userId = sessionUser(user);
        limit("connect-devices", userId, 30);
        devices.revoke(userId, id);
        return Map.of("ok", true);
    }

    private static String sessionUser(Principal user) {
        if (user == null) throw DeviceService.rejected(HttpStatus.UNAUTHORIZED, "请先登录");
        return user.getName();
    }

    private void limit(String group, String key, int maximum) {
        limits.check(group, key, maximum, DeviceService::rejected);
    }
}
