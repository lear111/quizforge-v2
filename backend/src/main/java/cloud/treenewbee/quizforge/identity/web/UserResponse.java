package cloud.treenewbee.quizforge.identity.web;

import cloud.treenewbee.quizforge.identity.infrastructure.UserRow;

/** 对外只返回公开的账号字段，避免将密码哈希等持久化字段序列化。 */
public record UserResponse(User user) {

    public record User(String id, String email, String name) {
    }

    static UserResponse from(UserRow row) {
        return new UserResponse(new User(row.id(), row.email(), row.name()));
    }
}
