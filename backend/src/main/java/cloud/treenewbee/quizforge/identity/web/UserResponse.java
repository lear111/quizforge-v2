package cloud.treenewbee.quizforge.identity.web;

import java.util.List;

import cloud.treenewbee.quizforge.identity.application.AccountService.Profile;

/** 对外只返回公开的账号字段，避免将密码哈希等持久化字段序列化。 */
public record UserResponse(User user) {

    public record User(String id, String email, String phone, String name, boolean admin,
                       List<String> unverifiedContacts) {
    }

    static UserResponse from(Profile profile) {
        return new UserResponse(new User(profile.id(), profile.email(), profile.phone(), profile.name(),
                profile.admin(), profile.unverifiedContacts()));
    }
}
