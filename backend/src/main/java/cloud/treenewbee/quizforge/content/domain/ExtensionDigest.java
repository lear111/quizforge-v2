package cloud.treenewbee.quizforge.content.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** C-04 review identity, computed exclusively from the archive's in-memory files. */
public final class ExtensionDigest {
    private ExtensionDigest() {}

    public record FileEntry(String path, long size, String sha256) {}
    public record Result(String digest, List<FileEntry> files) {}

    public static String compute(Map<String, byte[]> files) { return inspect(files).digest(); }

    public static Result inspect(Map<String, byte[]> input) {
        if (input == null || input.size() > 2000) throw new IllegalArgumentException("Invalid extension file count");
        Node root = new Node();
        long total = 0;
        for (Map.Entry<String, byte[]> file : input.entrySet()) {
            String name = file.getKey();
            byte[] bytes = file.getValue();
            if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0
                    || bytes == null || bytes.length > 8 * 1024 * 1024 || (total += bytes.length) > 64L * 1024 * 1024) throw new IllegalArgumentException("Invalid extension file");
            Node node = root;
            for (String part : name.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..") || node.bytes != null) throw new IllegalArgumentException("Invalid extension path");
                node = node.children.computeIfAbsent(part, ignored -> new Node());
            }
            if (!node.children.isEmpty()) throw new IllegalArgumentException("Conflicting extension paths");
            node.bytes = bytes.clone();
        }
        Node manifestNode = root.children.get("manifest.json");
        byte[] manifestBytes = manifestNode == null ? null : manifestNode.bytes;
        if (manifestBytes == null) throw new IllegalArgumentException("Missing manifest.json");
        Object parsed = EcmaJson.parse(manifestBytes);
        if (!(parsed instanceof Map<?, ?> manifest)) throw new IllegalArgumentException("Invalid manifest");
        if (!(manifest.get("examples") instanceof String examples) || !examples.matches("examples/[A-Za-z0-9._/-]+\\.json")
                || List.of(examples.split("/", -1)).contains("..")) throw new IllegalArgumentException("Invalid manifest examples path");
        Map<String, Object> normalized = new LinkedHashMap<>();
        manifest.forEach((key, value) -> normalized.put((String) key, value));
        normalized.put("id", manifest.get("id") == null ? "extension" : manifest.get("id"));
        normalized.put("version", manifest.get("version") == null ? "0.0.0" : manifest.get("version"));
        normalized.put("name", "@display-name");
        List<FileEntry> files = new ArrayList<>();
        visit(root, "", examples, normalized, files);
        List<Object> inventory = new ArrayList<>();
        for (FileEntry file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path()); item.put("size", file.size()); item.put("sha256", file.sha256());
            inventory.add(item);
        }
        return new Result(hash(EcmaJson.stringify(List.of("qfh-extension-review-v1", inventory), false).getBytes(StandardCharsets.UTF_8)), List.copyOf(files));
    }

    private static void visit(Node node, String path, String examples, Map<String, Object> manifest, List<FileEntry> files) {
        if (node.bytes == null) {
            node.children.forEach((name, child) -> visit(child, path.isEmpty() ? name : path + "/" + name, examples, manifest, files));
        } else {
            byte[] bytes = node.bytes;
            if (path.equals("manifest.json")) bytes = EcmaJson.stringify(manifest, true).getBytes(StandardCharsets.UTF_8);
            else if (path.equals(examples)) {
                Object bank = EcmaJson.parse(bytes);
                normalizeBindings(bank);
                bytes = EcmaJson.stringify(bank, true).getBytes(StandardCharsets.UTF_8);
            }
            files.add(new FileEntry(path, bytes.length, hash(bytes)));
        }
    }

    private static void normalizeBindings(Object value) {
        Deque<Object> pending = new ArrayDeque<>();
        if (value != null) pending.push(value);
        while (!pending.isEmpty()) {
            Object current = pending.pop();
            Iterable<?> children;
            if (current instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
                if (map.get("extension") instanceof Map<?, ?> extension && extension.size() == 1 && extension.get("path") instanceof String) {
                    map.put("extension", Map.of("path", "@package-extension"));
                }
                children = map.values();
            } else if (current instanceof List<?> list) children = list;
            else continue;
            for (Object child : children) if (child instanceof Map<?, ?> || child instanceof List<?>) pending.push(child);
        }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Node {
        private final Map<String, Node> children = new TreeMap<>();
        private byte[] bytes;
    }
}
