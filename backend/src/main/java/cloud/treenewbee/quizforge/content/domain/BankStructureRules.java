package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.INVALID_QUESTION_BANK;
import static cloud.treenewbee.quizforge.content.domain.PackageRules.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class BankStructureRules {
    private BankStructureRules() {}

    static Set<String> validate(String name, Map<String, Object> bank, Map<String, QbankPackageParser.Asset> assets,
            Map<String, Map<String, Object>> extensions, String exampleOwner) {
        Consumer<String> rootFail = fail(name, null);
        keys(bank, Set.of("formatVersion", "id", "version", "title", "description", "extension", "features", "listening", "questions"), rootFail);
        if (!integer(bank.get("formatVersion"), 2, 2)) rootFail.accept("formatVersion 必须显式为整数 2");
        if (bank.containsKey("title")) text(bank, "title", 300, rootFail);
        optionalDescription(bank, rootFail);
        if (bank.containsKey("features")) {
            Map<String, Object> features = object(bank.get("features"), rootFail);
            keys(features, Set.of("editing", "whiteboard", "history", "exam"), rootFail);
            for (Object value : features.values()) if (!(value instanceof Boolean)) rootFail.accept("features 只能包含布尔值");
        }
        Set<String> materialIds = listening(bank, assets, rootFail);
        Set<String> used = new HashSet<>();
        String fallback = bank.containsKey("extension") ? binding(bank.get("extension"), extensions, exampleOwner, rootFail) : null;
        if (fallback != null) used.add(fallback);
        Set<String> ids = new HashSet<>();
        for (Object raw : array(bank.get("questions"), 1, 10_000, rootFail)) {
            Map<String, Object> question = object(raw, rootFail);
            Object rawId = question.get("id");
            Consumer<String> questionFail = fail(name, rawId instanceof String id ? id : "<未提供>");
            keys(question, Set.of("id", "title", "extension", "outline", "listening", "data"), questionFail);
            if (!(rawId instanceof String id) || !ID.matcher(id).matches() || !ids.add(id)) questionFail.accept("题目 ID 无效或重复");
            text(question, "title", 300, questionFail);
            if (!question.containsKey("data")) questionFail.accept("缺少 data");
            String owner = question.containsKey("extension") ? binding(question.get("extension"), extensions, exampleOwner, questionFail) : fallback;
            if (owner == null) questionFail.accept("缺少有效的拓展绑定");
            used.add(owner);
            if (question.containsKey("outline")) outline(question.get("outline"), extensions.get(owner), questionFail);
            if (question.containsKey("listening")) {
                Map<String, Object> listening = object(question.get("listening"), questionFail);
                keys(listening, Set.of("materialId"), questionFail);
                if (!(listening.get("materialId") instanceof String id) || !materialIds.contains(id)) questionFail.accept("听力材料不存在");
            }
            images(question.get("data"), assets, questionFail);
        }
        return used;
    }

    private static String binding(Object raw, Map<String, Map<String, Object>> extensions, String exampleOwner, Consumer<String> fail) {
        Map<String, Object> reference = object(raw, fail);
        keys(reference, Set.of("path", "id"), fail);
        String path = text(reference, "path", 1000, fail);
        if (!path.startsWith("extensions/") || path.split("/", -1).length != 3 || !extensions.containsKey(path)) fail.accept("拓展必须按准确路径指向本包内的拓展：" + path);
        if (reference.containsKey("id")) {
            String id = text(reference, "id", 120, fail);
            if (!ID.matcher(id).matches() || !id.equals(extensions.get(path).get("id"))) fail.accept("绑定的拓展 ID 与 manifest 不一致：" + path);
        }
        if (exampleOwner != null && !exampleOwner.equals(path)) fail.accept("拓展示例必须绑定所属拓展自己");
        return path;
    }

    private static Set<String> listening(Map<String, Object> bank, Map<String, QbankPackageParser.Asset> assets, Consumer<String> fail) {
        Set<String> ids = new HashSet<>();
        if (!bank.containsKey("listening")) return ids;
        Map<String, Object> config = object(bank.get("listening"), fail);
        keys(config, Set.of("materials", "examMaterialId"), fail);
        for (Object raw : array(config.get("materials"), 1, 50, fail)) {
            Map<String, Object> row = object(raw, fail);
            keys(row, Set.of("id", "title", "file"), fail);
            if (!(row.get("id") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,80}") || !ids.add(id)) fail.accept("听力材料 ID 无效或重复");
            text(row, "title", 160, fail);
            if (!(row.get("file") instanceof String file) || !file.startsWith("assets/") || file.split("/", -1).length != 2
                    || !assets.containsKey(file) || assets.get(file).type() != ResourceType.AUDIO) fail.accept("听力材料必须指向本题库 assets/ 中的音频");
        }
        if (config.containsKey("examMaterialId") && !ids.contains(config.get("examMaterialId"))) fail.accept("examMaterialId 指向未声明的听力材料");
        return ids;
    }

    private static void outline(Object raw, Map<String, Object> extension, Consumer<String> fail) {
        Map<String, Object> outline = object(raw, fail);
        if ("question".equals(outline.get("level"))) {
            keys(outline, Set.of("level", "label"), fail);
            if (outline.size() != 2 || !plain(outline.get("label"), 80)) fail.accept("大题 outline 标签必须为 1～80 字符的纯文本");
            return;
        }
        keys(outline, Set.of("level", "items"), fail);
        if (!"parts".equals(outline.get("level")) || outline.size() != 2) fail.accept("outline 必须选择 question 或 parts");
        Set<String> ids = new HashSet<>();
        for (Object value : array(outline.get("items"), 1, 100, fail)) {
            Map<String, Object> item = object(value, fail);
            keys(item, Set.of("id", "label"), fail);
            if (item.size() != 2 || !plain(item.get("id"), 128) || !plain(item.get("label"), 80) || !ids.add((String) item.get("id"))) fail.accept("小题必须含唯一纯文本 id（最多 128 字符）和 label（最多 80 字符）");
        }
        if (!LegacyExtensionRules.outlineDeclared(extension)) fail.accept("显式小题目录需要所属拓展声明 API 1.1 outline-items 能力");
    }

    private static void images(Object data, Map<String, QbankPackageParser.Asset> assets, Consumer<String> fail) {
        Set<String> hashes = new HashSet<>();
        for (QbankPackageParser.Asset asset : assets.values()) if (asset.type() == ResourceType.IMAGE) hashes.add(asset.sha256());
        ArrayDeque<Object> pending = new ArrayDeque<>();
        if (data != null) pending.push(data);
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            Iterable<?> children;
            if (value instanceof Map<?, ?> map) {
                if ("image".equals(map.get("type")) && map.get("attrs") instanceof Map<?, ?> attrs && attrs.containsKey("assetId")) {
                    Object id = attrs.get("assetId");
                    if (!(id instanceof String hash) || !hash.matches("[a-f0-9]{64}") || !hashes.contains(hash)) fail.accept("引用了不存在的图片：" + id);
                }
                children = map.values();
            } else if (value instanceof List<?> list) children = list;
            else continue;
            for (Object child : children) if (child != null) pending.push(child);
        }
    }

    private static Consumer<String> fail(String bank, String question) {
        return detail -> { throw new PackageRejectedException(INVALID_QUESTION_BANK,
                "题库「" + bank + "」" + (question == null ? "" : "，题目「" + question + "」") + "：" + detail); };
    }
}
