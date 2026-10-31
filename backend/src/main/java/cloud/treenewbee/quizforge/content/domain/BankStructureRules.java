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
        keys(bank, Set.of("formatVersion", "id", "version", "title", "description", "extension", "features", "listening", "outline", "questions"), rootFail);
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
        Map<String, Map<String, Object>> questionExtensions = new java.util.LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : array(bank.get("questions"), 1, 10_000, rootFail)) {
            Map<String, Object> question = object(raw, rootFail);
            Object rawId = question.get("id");
            Consumer<String> questionFail = fail(name, rawId instanceof String id ? id : "<未提供>");
            keys(question, Set.of("id", "title", "extension", "listening", "data"), questionFail);
            if (!(rawId instanceof String id) || !ID.matcher(id).matches() || !ids.add(id)) questionFail.accept("题目 ID 无效或重复");
            text(question, "title", 300, questionFail);
            if (!question.containsKey("data")) questionFail.accept("缺少 data");
            String owner = question.containsKey("extension") ? binding(question.get("extension"), extensions, exampleOwner, questionFail) : fallback;
            if (owner == null) questionFail.accept("缺少有效的拓展绑定");
            used.add(owner);
            questionExtensions.put((String) rawId, extensions.get(owner));
            if (question.containsKey("listening")) {
                Map<String, Object> listening = object(question.get("listening"), questionFail);
                keys(listening, Set.of("materialId"), questionFail);
                if (!(listening.get("materialId") instanceof String id) || !materialIds.contains(id)) questionFail.accept("听力材料不存在");
            }
            images(question.get("data"), assets, questionFail);
        }
        centralOutline(bank.get("outline"), questionExtensions, rootFail);
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

    private static void centralOutline(Object raw, Map<String, Map<String, Object>> extensions, Consumer<String> fail) {
        List<Object> groups = array(raw, 1, 10_000, detail -> fail.accept("outline：" + detail));
        Set<String> referenced = new HashSet<>();
        for (int g = 0; g < groups.size(); g++) {
            String location = "outline[" + g + "]";
            Consumer<String> groupFail = detail -> fail.accept(location + "：" + detail);
            Map<String, Object> group = object(groups.get(g), groupFail);
            keys(group, Set.of("group", "items"), groupFail);
            if (group.containsKey("group") && !plain(group.get("group"), 80)) groupFail.accept("group 必须为 1～80 字符的纯文本");
            List<Object> items = array(group.get("items"), 1, 10_000, detail -> fail.accept(location + ".items：" + detail));
            for (int i = 0; i < items.size(); i++) {
                String at = location + ".items[" + i + "]";
                Consumer<String> itemFail = detail -> fail.accept(at + "：" + detail);
                Map<String, Object> item = object(items.get(i), itemFail);
                keys(item, Set.of("question", "label", "parts"), itemFail);
                Object id = item.get("question");
                if (!(id instanceof String) || !extensions.containsKey(id)) fail.accept(at + ".question：引用了不存在的题目「" + id + "」");
                if (!referenced.add((String) id)) fail.accept(at + ".question：重复引用题目「" + id + "」");
                if (item.containsKey("label") && item.containsKey("parts")) itemFail.accept("label 与 parts 不可同时出现");
                if (item.containsKey("label") && !plain(item.get("label"), 80)) itemFail.accept("label 必须为 1～80 字符的纯文本");
                if (!item.containsKey("parts")) continue;
                if (!LegacyExtensionRules.outlineDeclared(extensions.get(id))) itemFail.accept("parts 需要题目「" + id + "」的拓展声明 outline-items 能力");
                List<Object> parts = array(item.get("parts"), 1, 100, detail -> fail.accept(at + ".parts：" + detail));
                Set<String> partIds = new HashSet<>();
                for (int j = 0; j < parts.size(); j++) {
                    String partAt = at + ".parts[" + j + "]";
                    Consumer<String> partFail = detail -> fail.accept(partAt + "：" + detail);
                    Map<String, Object> part = object(parts.get(j), partFail);
                    keys(part, Set.of("id", "label"), partFail);
                    if (!plain(part.get("id"), 128) || !partIds.add((String) part.get("id"))) partFail.accept("id 必须为唯一的 1～128 字符纯文本");
                    if (!plain(part.get("label"), 80)) partFail.accept("label 必须为 1～80 字符的纯文本");
                }
            }
        }
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
