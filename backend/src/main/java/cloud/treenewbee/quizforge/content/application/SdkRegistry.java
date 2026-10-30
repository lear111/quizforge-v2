package cloud.treenewbee.quizforge.content.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resolve the fork's pinned richtext registry, including the legacy version aliases. */
@Component
public class SdkRegistry {
    public record Dependency(String id, String version) {}
    public record Resolution(List<Dependency> dependencies, JsonNode contentApi) {}
    private final JsonNode registry;
    private final JsonMapper json;

    public SdkRegistry(JsonMapper json) throws IOException {
        this.json = json;
        try (var input = new ClassPathResource("frontend/richtext/service.json").getInputStream()) {
            registry = json.readTree(input);
        }
    }

    public Resolution resolve(JsonNode manifest) {
        JsonNode requirement = manifest.get("requiresRichText");
        if (requirement != null) {
            for (JsonNode profile : registry.path("profiles")) {
                if (profile.path("id").asString().equals(requirement.path("documentProfile").asString())) {
                    return profile(profile);
                }
            }
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_RICHTEXT_PROFILE", "不支持此题型的富文本配置");
        }
        List<Dependency> dependencies = new ArrayList<>();
        JsonNode api = null;
        for (JsonNode dependency : manifest.path("dependencies")) {
            String version = dependency.path("version").asString();
            JsonNode matched = null;
            for (JsonNode profile : registry.path("profiles")) {
                for (JsonNode alias : profile.path("legacyVersions")) if (alias.asString().equals(version)) matched = profile;
            }
            if (matched == null) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SDK_UNAVAILABLE", "题型需要的组件版本尚未安装");
            Resolution resolution = profile(matched);
            dependencies.addAll(resolution.dependencies()); api = resolution.contentApi();
        }
        return new Resolution(List.copyOf(dependencies), api);
    }

    private Resolution profile(JsonNode profile) {
        var api = json.createObjectNode();
        api.put("major", registry.path("api").path("major").asInt());
        api.put("minor", registry.path("api").path("minor").asInt());
        api.put("documentFormat", registry.path("api").path("documentFormat").asInt());
        api.put("documentProfile", profile.path("id").asString());
        api.set("capabilities", profile.path("capabilities"));
        return new Resolution(List.of(new Dependency(profile.path("provider").path("id").asString(),
                profile.path("provider").path("version").asString())), api);
    }

    public boolean permits(JsonNode manifest, String id, String version) {
        if (id.equals("quizforge.answer-area")) return version.equals("1.4.1") && declares(manifest, "answer-areas");
        try { return resolve(manifest).dependencies().contains(new Dependency(id, version)); }
        catch (ApiException unavailable) { return false; }
    }

    public static boolean declares(JsonNode manifest, String name) {
        for (JsonNode capability : manifest.path("requiresApi").path("capabilities")) if (capability.asString().equals(name)) return true;
        return false;
    }
}
