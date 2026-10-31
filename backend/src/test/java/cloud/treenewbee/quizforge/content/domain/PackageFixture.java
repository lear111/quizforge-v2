package cloud.treenewbee.quizforge.content.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/** Real runner fixtures, adapted only in test memory to format 4/2, with missing UI/image test files. */
final class PackageFixture {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String GROUP = "基础题型";
    static final String BANK = "question-banks/" + GROUP + "/练习";
    static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    final Map<String, byte[]> files = new LinkedHashMap<>();
    final String extension;
    final Map<String, byte[]> stored = new LinkedHashMap<>();

    PackageFixture(String type) throws IOException {
        extension = "extensions/" + GROUP + "/" + type;
        addExtension(type);
        put("quizforge-package.json", Map.of("format", "quizforge-bank-group", "formatVersion", 4, "group", GROUP));
        Map<String, Object> examples = document(files.get(extension + "/examples/bank.json"));
        Map<String, Object> first = object(((List<?>) examples.get("questions")).get(0));
        put(BANK + "/bank.json", Map.of("formatVersion", 2, "id", ImportArchives.BANK_IDS.get(type), "title", "作者元数据名称", "extension", Map.of("path", extension),
                "outline", List.of(Map.of("items", List.of(Map.of("question", "q1")))),
                "questions", List.of(Map.of("id", "q1", "title", "第 1 题", "data", first.get("data")))));
        files.put(BANK + "/assets/自由图.PNG", PNG.clone());
    }

    void addExtension(String name) throws IOException {
        Path directory = Path.of(System.getProperty("basedir", ".")).resolve("../rule-runner/test/fixtures").normalize();
        String fixtureName = Files.isDirectory(directory.resolve(name)) ? name : "单选题";
        Path source = directory.resolve(fixtureName);
        String owner = "extensions/" + GROUP + "/" + name;
        try (var stream = Files.walk(source)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) files.put(owner + "/" + source.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        files.put(owner + "/practice.html", "<!doctype html><html lang='zh'></html>".getBytes(StandardCharsets.UTF_8));
        files.put(owner + "/practice.js", "// test page".getBytes(StandardCharsets.UTF_8));
        files.put(owner + "/practice.css", "body{}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> example = document(files.get(owner + "/examples/bank.json"));
        example.put("formatVersion", 2);
        example.put("id", ImportArchives.exampleId(name));
        centralizeOutline(example);
        bind(example, owner);
        for (Object question : (List<?>) example.get("questions")) bind(object(question), owner);
        ArrayDeque<Object> pending = new ArrayDeque<>(); pending.push(example);
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            Iterable<?> children;
            if (value instanceof Map<?, ?> raw) {
                Map<String, Object> map = object(raw);
                if ("image".equals(map.get("type")) && map.get("attrs") instanceof Map<?, ?> attrs && attrs.containsKey("assetId")) object(attrs).put("assetId", QbankPackageParser.hash(PNG));
                children = map.values();
            } else if (value instanceof List<?> list) children = list;
            else continue;
            for (Object child : children) if (child != null) pending.push(child);
        }
        put(owner + "/examples/bank.json", example);
        files.put(owner + "/examples/assets/示例.png", PNG.clone());
    }

    void bank(Consumer<Map<String, Object>> mutate) { edit(BANK + "/bank.json", mutate); }
    void manifest(Consumer<Map<String, Object>> mutate) { edit(extension + "/manifest.json", mutate); }
    void marker(Consumer<Map<String, Object>> mutate) { edit("quizforge-package.json", mutate); }
    void question(Consumer<Map<String, Object>> mutate) { bank(bank -> mutate.accept(object(((List<?>) bank.get("questions")).get(0)))); }
    void edit(String path, Consumer<Map<String, Object>> mutate) {
        Map<String, Object> raw = document(files.get(path)); mutate.accept(raw); put(path, raw);
    }
    void put(String path, Object value) { files.put(path, JSON.writeValueAsBytes(value)); }
    QbankPackageParser parser() { return new QbankPackageParser(bytes -> { String hash = QbankPackageParser.hash(bytes); stored.putIfAbsent(hash, bytes.clone()); return hash; }); }
    QbankPackageParser.ParsedPackage parse() {
        QbankPackageParser parser = parser(); files.forEach(parser::accept); return parser.finish();
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static Map<String, Object> document(byte[] bytes) { return JSON.readValue(bytes, LinkedHashMap.class); }
    static void centralizeOutline(Map<String, Object> bank) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : (List<?>) bank.get("questions")) {
            Map<String, Object> question = object(raw);
            Map<String, Object> item = new LinkedHashMap<>(); item.put("question", question.get("id"));
            Object old = question.containsKey("outline") ? question.remove("outline") : null;
            if (old instanceof Map<?, ?> outline) {
                if ("question".equals(outline.get("level"))) item.put("label", outline.get("label"));
                else item.put("parts", outline.get("items"));
            }
            items.add(item);
        }
        bank.put("outline", List.of(Map.of("items", items)));
    }
    private static void bind(Map<String, Object> value, String owner) { if (value.containsKey("extension")) value.put("extension", Map.of("path", owner)); }
}
