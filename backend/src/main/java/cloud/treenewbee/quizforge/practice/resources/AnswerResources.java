package cloud.treenewbee.quizforge.practice.resources;

import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.content.infrastructure.ContentAccessMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;

/** Resource ownership is independent of content addressing; an unguessable hash is not authorization. */
@Component
public class AnswerResources {
    private final UserResourceMapper resources;
    private final ContentAccessMapper access;
    public AnswerResources(UserResourceMapper resources, ContentAccessMapper access) { this.resources = resources; this.access = access; }

    public void validate(String userId, JsonNode answer) {
        for (String id : references(answer)) {
            byte[] hash = HexFormat.of().parseHex(id);
            if (resources.find(userId, hash).isEmpty() && access.asset(userId, hash).isEmpty())
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IMAGE_REFERENCE_UNAVAILABLE", "作答引用的图片不存在或无权访问");
        }
    }

    public static Set<String> references(JsonNode document) {
        Set<String> ids = new LinkedHashSet<>();
        var pending = new ArrayDeque<JsonNode>();
        if (document != null) pending.add(document);
        while (!pending.isEmpty()) {
            var value = pending.removeLast();
            if (value.isObject() && value.has("assetId")) {
                var id = value.get("assetId");
                if (!id.isString() || !id.asString().matches("[a-f0-9]{64}"))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "图片引用编号无效");
                ids.add(id.asString());
                if (ids.size() > 20)
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_COUNT_LIMIT", "每道题最多引用 20 张图片（含提交截图）");
            }
            if (value.isObject() || value.isArray()) for (var child : value) pending.add(child);
        }
        return java.util.Collections.unmodifiableSet(ids);
    }
}
