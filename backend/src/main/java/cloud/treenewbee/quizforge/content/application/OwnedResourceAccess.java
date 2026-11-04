package cloud.treenewbee.quizforge.content.application;

import java.util.Optional;

/** 内容读取权限端口；资源归属的具体实现由拥有资源的模块提供。 */
public interface OwnedResourceAccess {
    Optional<String> ownedMime(String userId, byte[] sha256);
}
