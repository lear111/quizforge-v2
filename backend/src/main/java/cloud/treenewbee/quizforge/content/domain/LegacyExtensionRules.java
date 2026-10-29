package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.INVALID_EXTENSION;
import static cloud.treenewbee.quizforge.content.domain.PackageRules.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Fixed 4522bb8 host rules, with format-4 overrides recorded in design 06 appendix A. */
final class LegacyExtensionRules {
    static final List<String> FILE_FIELDS = List.of("entry", "script", "style", "rules", "questionSchema", "answerSchema", "examples");
    static final Set<String> CAPABILITIES = Set.of("practice", "editor", "editor-drafts", "score", "manual-review", "ai-grading",
            "resources", "richtext", "navigation", "lifecycle", "outline-items", "answer-state", "appearance", "answer-areas");
    static final Set<String> RICH_CAPABILITIES = Set.of("basic-formatting", "images", "advanced-formatting", "tables", "math", "image-resize", "camera-capture");
    private static final Set<String> MANIFEST_FIELDS = Set.of("id", "version", "name", "description", "entry", "script", "style", "rules",
            "questionSchema", "answerSchema", "examples", "requiresApi", "requiresRichText", "dependencies", "assets");
    private LegacyExtensionRules() {}

    static void validate(String path, Map<String, Object> manifest, Map<String, byte[]> files) {
        Consumer<String> fail = detail -> { throw new PackageRejectedException(INVALID_EXTENSION, "拓展「" + path + "」：" + detail); };
        keys(manifest, MANIFEST_FIELDS, fail);
        for (String key : List.of("id", "version")) {
            String value = text(manifest, key, 120, fail);
            if (!ID.matcher(value).matches()) fail.accept(key + " 格式无效");
        }
        text(manifest, "name", 300, fail);
        optionalDescription(manifest, fail);
        for (String field : FILE_FIELDS) {
            byte[] bytes = requireFile(text(manifest, field, 240, fail), files, fail);
            if (Set.of("entry", "script", "style").contains(field)) utf8(bytes, fail);
        }
        String examples = (String) manifest.get("examples");
        if (!examples.matches("examples/[A-Za-z0-9._/-]+\\.json") || List.of(examples.split("/", -1)).contains("..")) fail.accept("examples 必须是 examples/ 下的 JSON 文件");
        api(manifest, fail);
        richText(manifest, fail);
        if (manifest.containsKey("assets")) {
            Set<String> unique = new HashSet<>();
            for (Object item : array(manifest.get("assets"), 0, Integer.MAX_VALUE, fail)) {
                if (!(item instanceof String value)) { fail.accept("assets 必须为路径字符串数组"); continue; }
                unique.add(value);
            }
            if (unique.size() > 100) fail.accept("assets 不得超过 100 个不同路径");
            long total = 0;
            for (String asset : unique) {
                if (!text(asset, 240)) fail.accept("assets 路径必须为非空字符串且不超过 240 字符");
                String suffix = asset.substring(asset.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("png", "jpg", "jpeg", "webp", "gif", "svg", "woff", "woff2", "ttf", "otf").contains(suffix)) fail.accept("静态资源仅支持图片和字体");
                total += requireFile(asset, files, fail).length;
            }
            if (total > 8L * 1024 * 1024) fail.accept("内联静态资源总量超过 8 MiB");
        }
        if (files.containsKey("editor.json")) {
            Map<String, Object> editor = object(json(files.get("editor.json"), fail), fail);
            keys(editor, Set.of("entry", "script", "style"), fail);
            for (String field : List.of("entry", "script", "style")) utf8(requireFile(text(editor, field, 240, fail), files, fail), fail);
        }
    }

    static void api(Map<String, Object> manifest, Consumer<String> fail) {
        if (!manifest.containsKey("requiresApi")) return;
        Map<String, Object> api = object(manifest.get("requiresApi"), fail);
        keys(api, Set.of("major", "minMinor", "capabilities"), fail);
        if (!integer(api.get("major"), 1, Integer.MAX_VALUE) || !integer(api.get("minMinor"), 0, Integer.MAX_VALUE)) fail.accept("requiresApi 版本必须为整数");
        if (!integer(api.get("major"), 1, 1) || !integer(api.get("minMinor"), 0, 5)) fail.accept("仅支持 Extension API 1.0～1.5");
        Set<String> requested = capabilities(api, "capabilities", CAPABILITIES, Integer.MAX_VALUE, fail);
        for (var gate : Map.of("outline-items", 1, "answer-state", 3, "appearance", 4, "answer-areas", 5).entrySet()) {
            if (requested.contains(gate.getKey()) && !integer(api.get("minMinor"), gate.getValue(), 5)) fail.accept(gate.getKey() + " 需要 minMinor 至少为 " + gate.getValue());
        }
    }

    static boolean outlineDeclared(Map<String, Object> manifest) {
        if (!(manifest.get("requiresApi") instanceof Map<?, ?> api)) return false;
        return integer(api.get("major"), 1, 1) && integer(api.get("minMinor"), 1, Integer.MAX_VALUE)
                && api.get("capabilities") instanceof List<?> list && list.contains("outline-items");
    }

    private static void richText(Map<String, Object> manifest, Consumer<String> fail) {
        if (manifest.containsKey("requiresRichText") && manifest.containsKey("dependencies")) fail.accept("requiresRichText 与 dependencies 不能同时声明");
        if (manifest.containsKey("requiresRichText")) {
            Map<String, Object> rich = object(manifest.get("requiresRichText"), fail);
            keys(rich, Set.of("major", "minMinor", "documentFormat", "documentProfile", "capabilities"), fail);
            if (!integer(rich.get("major"), 1, 1) || !integer(rich.get("minMinor"), 0, 1) || !integer(rich.get("documentFormat"), 1, 1)) fail.accept("不支持的富文本接口或文档版本");
            if (!(rich.get("documentProfile") instanceof String profile) || !Set.of("basic-v1", "advanced-v1").contains(profile)) fail.accept("不支持的富文本文档配置");
            Set<String> allowed = "basic-v1".equals(rich.get("documentProfile")) ? Set.of("basic-formatting", "images") : RICH_CAPABILITIES;
            capabilities(rich, "capabilities", allowed, 100, fail);
        }
        if (manifest.containsKey("dependencies")) {
            Set<String> seen = new HashSet<>();
            for (Object item : array(manifest.get("dependencies"), 0, 4, fail)) {
                Map<String, Object> dependency = object(item, fail);
                keys(dependency, Set.of("id", "version"), fail);
                if (!"quizforge.richtext".equals(dependency.get("id")) || !text(dependency.get("version"), 120)
                        || !((String) dependency.get("version")).matches("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}") || !seen.add((String) dependency.get("id"))) fail.accept("无效或重复的 SDK 依赖");
            }
        }
    }

    private static Set<String> capabilities(Map<String, Object> owner, String key, Set<String> allowed, int maxLength, Consumer<String> fail) {
        Set<String> seen = new HashSet<>();
        if (owner.containsKey(key)) for (Object item : array(owner.get(key), 0, 100, fail)) {
            if (!text(item, maxLength) || !allowed.contains(item) || !seen.add((String) item)) fail.accept("能力声明必须为已支持、非空且不重复的字符串");
        }
        return seen;
    }

    static Set<String> declared(Map<String, Object> manifest, Map<String, byte[]> files) {
        Set<String> result = new HashSet<>();
        for (String field : FILE_FIELDS) if (manifest.get(field) instanceof String value) addDeclared(result, value);
        if (manifest.get("assets") instanceof List<?> assets) for (Object value : assets) if (value instanceof String path) addDeclared(result, path);
        if (files.containsKey("editor.json")) try {
            if (PackageJson.read(files.get("editor.json")) instanceof Map<?, ?> editor) {
                for (String field : List.of("entry", "script", "style")) if (editor.get(field) instanceof String path) addDeclared(result, path);
            }
        } catch (RuntimeException ignored) { /* The manifest phase reports invalid editor JSON. */ }
        return result;
    }
    private static void addDeclared(Set<String> result, String path) {
        try { result.add(relative(path)); } catch (IllegalArgumentException ignored) { /* Reported by validate(). */ }
    }
    static String relative(String raw) {
        if (raw.startsWith("/") || raw.contains("\\") || raw.contains(":") || raw.indexOf('\0') >= 0) throw new IllegalArgumentException("路径越界");
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) throw new IllegalArgumentException("路径越界"); parts.removeLast(); }
            else parts.add(part);
        }
        return String.join("/", parts);
    }
    private static byte[] requireFile(String raw, Map<String, byte[]> files, Consumer<String> fail) {
        String path;
        try { path = relative(raw); } catch (IllegalArgumentException error) { fail.accept("文件路径无效：" + raw); return null; }
        byte[] bytes = files.get(path);
        if (bytes == null) fail.accept("缺少声明的文件：" + raw);
        return bytes;
    }
    private static Object json(byte[] bytes, Consumer<String> fail) {
        try { return PackageJson.read(bytes); } catch (RuntimeException error) { fail.accept("editor.json 必须为有效 UTF-8 JSON"); return null; }
    }
    private static void utf8(byte[] bytes, Consumer<String> fail) {
        try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)); }
        catch (java.nio.charset.CharacterCodingException error) { fail.accept("页面文件必须为有效的 UTF-8"); }
    }
}
