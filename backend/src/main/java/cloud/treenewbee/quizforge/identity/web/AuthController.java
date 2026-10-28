package cloud.treenewbee.quizforge.identity.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.identity.application.AuthService.AuthResult;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int USER_AGENT_LENGTH = 255;
    private final AuthService auth;
    private final UserMapper users;

    public AuthController(AuthService auth, UserMapper users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody AuthRequests.Register body,
            HttpServletRequest request) {
        return authenticated(auth.register(body.email(), body.name(), body.password(), userAgent(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody AuthRequests.Login body,
            HttpServletRequest request) {
        return authenticated(auth.login(body.email(), body.password(), userAgent(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(SessionCookies.read(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, SessionCookies.clear().toString()).build();
    }

    private ResponseEntity<UserResponse> authenticated(AuthResult result) {
        var user = users.findById(result.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.create(result.token()).toString())
                .body(UserResponse.from(user));
    }

    private static String userAgent(HttpServletRequest request) {
        String value = request.getHeader(HttpHeaders.USER_AGENT);
        if (value == null) {
            return null;
        }
        int length = Math.min(value.codePointCount(0, value.length()), USER_AGENT_LENGTH);
        return value.substring(0, value.offsetByCodePoints(0, length));
    }
}
