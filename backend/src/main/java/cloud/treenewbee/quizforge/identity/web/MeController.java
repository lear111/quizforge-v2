package cloud.treenewbee.quizforge.identity.web;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;

@RestController
public class MeController {

    private final UserMapper users;

    public MeController(UserMapper users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public UserResponse me(Principal principal) {
        var user = users.findById(principal.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"));
        return UserResponse.from(user);
    }
}
