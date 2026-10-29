package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.*;
import static cloud.treenewbee.quizforge.content.domain.PackageRules.*;

import cloud.treenewbee.quizforge.content.archive.QbankArchiveReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Single-use, file-event parser. No business writes or rule execution; unreferenced blobs may remain on failure. */
public final class QbankPackageParser implements QbankArchiveReader.EntryHandler {
    private static final String MARKER = "quizforge-package.json";
    private static final int JSON_LIMIT = 256 * 1024;
    private static final int FILE_LIMIT = 8 * 1024 * 1024;

    @FunctionalInterface
    public interface AssetSink {
        /** Consumes the bytes synchronously; returns their lowercase SHA-256. Also stores extension files. */
        String store(byte[] bytes) throws IOException;
    }

    public record PackageManifest(String group, String scope) {}
    public record Asset(String sha256, long size, ResourceType type) {}
    public record ParsedBank(String bankKey, String folderName, Map<String, Object> bank, Map<String, Asset> assets) {
        public ParsedBank { bank = immutableObject(bank); assets = Map.copyOf(assets); }
        public Map<String, Boolean> features() {
            Map<?, ?> supplied = bank.get("features") instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Boolean> result = new LinkedHashMap<>();
            for (String key : List.of("editing", "whiteboard", "history", "exam")) result.put(key, supplied.containsKey(key) ? (Boolean) supplied.get(key) : true);
            return Collections.unmodifiableMap(result);
        }
    }
    public record ParsedExtension(String path, Map<String, Object> manifest, Map<String, String> files, String digest) {
        public ParsedExtension { manifest = immutableObject(manifest); files = Map.copyOf(files); }
    }
    public record ParsedPackage(PackageManifest manifest, List<ParsedBank> banks, List<ParsedExtension> extensions) {
        public ParsedPackage { banks = List.copyOf(banks); extensions = List.copyOf(extensions); }
    }

    private record FileBytes(int size, byte[] bytes) {}
    private record Resource(Asset asset, PackageRejectedException failure) {}
    private final AssetSink sink;
    private final Map<String, FileBytes> files = new LinkedHashMap<>();
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();
    private boolean duplicate;
    private boolean finished;

    public QbankPackageParser(AssetSink sink) { this.sink = java.util.Objects.requireNonNull(sink); }

    @Override
    public void accept(String path, byte[] bytes) {
        if (finished) throw new IllegalStateException("解析器已经结束");
        java.util.Objects.requireNonNull(path); java.util.Objects.requireNonNull(bytes);
        if (!seen.add(path.toLowerCase(Locale.ROOT))) duplicate = true;
        String[] parts = path.split("/", -1);
        boolean asset = parts.length == 5 && parts[0].equals("question-banks") && parts[3].equals("assets");
        if (asset) {
            Resource resource;
            try {
                ResourceType type = ResourceType.detect(parts[4], bytes);
                resource = new Resource(new Asset(store(bytes), bytes.length, type), null);
            } catch (PackageRejectedException rejected) { resource = new Resource(null, rejected); }
            resources.put(path, resource);
            files.put(path, new FileBytes(bytes.length, null));
        } else {
            int limit = metadata(path) ? JSON_LIMIT : FILE_LIMIT;
            files.put(path, new FileBytes(bytes.length, bytes.length <= limit ? bytes.clone() : null));
        }
    }

    public ParsedPackage finish() {
        if (finished) throw new IllegalStateException("解析器已经结束");
        finished = true;
        try { return validate(); }
        finally { files.clear(); resources.clear(); seen.clear(); }
    }

    private ParsedPackage validate() {
        Map<String, Object> marker = json(MARKER, INVALID_LIBRARY_ARCHIVE, "格式标识");
        var invalidMarker = failure(INVALID_LIBRARY_ARCHIVE, "格式标识");
        keys(marker, Set.of("format", "formatVersion", "group", "scope"), invalidMarker);
        if (!"quizforge-bank-group".equals(marker.get("format"))) invalidMarker.accept("format 不正确");
        if (!integer(marker.get("formatVersion"), 4, 4)) {
            throw new PackageRejectedException(UNSUPPORTED_LIBRARY_ARCHIVE, integer(marker.get("formatVersion"), 3, 3)
                    ? "旧版题库包，请用新版客户端重新导出" : "不支持的题库包格式版本，仅支持格式 4");
        }
        String group = text(marker, "group", 120, invalidMarker);
        folder(group, invalidMarker);
        String scope = marker.containsKey("scope") && marker.get("scope") instanceof String value ? value : marker.containsKey("scope") ? "" : "group";
        if (!Set.of("group", "bank").contains(scope)) invalidMarker.accept("scope 必须为 group 或 bank");
        Map<String, List<String>> bankFiles = new LinkedHashMap<>(), extensionFiles = new LinkedHashMap<>();
        layout(group, bankFiles, extensionFiles);
        if (bankFiles.isEmpty() || bankFiles.size() > 1000 || extensionFiles.isEmpty() || extensionFiles.size() > 200) reject(INVALID_LIBRARY_ARCHIVE, "题库数量必须为 1～1000，拓展数量必须为 1～200");
        if (scope.equals("bank") && bankFiles.size() != 1) reject(INVALID_LIBRARY_ARCHIVE, "单题库包必须恰好包含一本题库");
        for (String bank : bankFiles.keySet()) if (!files.containsKey(bank + "/bank.json")) reject(INVALID_LIBRARY_ARCHIVE, "题库目录缺少 bank.json：" + bank);
        Map<String, Map<String, byte[]>> extensionBytes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        Map<String, PackageRejectedException> extensionFailures = new LinkedHashMap<>();
        for (var item : extensionFiles.entrySet()) {
            String path = item.getKey();
            if (!files.containsKey(path + "/manifest.json")) reject(INVALID_LIBRARY_ARCHIVE, "拓展目录缺少 manifest.json：" + path);
            Map<String, byte[]> content = new LinkedHashMap<>();
            for (String file : item.getValue()) {
                try { content.put(file.substring(path.length() + 1), requireBytes(file)); }
                catch (PackageRejectedException rejected) { extensionFailures.putIfAbsent(path, rejected); }
            }
            extensionBytes.put(path, content);
            Map<String, Object> manifest;
            try { manifest = json(path + "/manifest.json", INVALID_EXTENSION, "拓展「" + path + "」"); }
            catch (PackageRejectedException rejected) { extensionFailures.putIfAbsent(path, rejected); manifest = Map.of(); }
            manifests.put(path, manifest);
            Set<String> declared = LegacyExtensionRules.declared(manifest, content);
            if (!manifest.isEmpty()) for (String file : content.keySet()) if (privatePath(file) && !declared.contains(file)) reject(INVALID_LIBRARY_ARCHIVE, "拓展含未声明的私有文件：" + path + "/" + file);
        }
        // Asset failures are deferred so marker/layout rejection precedes resource rejection.
        for (Resource resource : resources.values()) if (resource.failure() != null) throw resource.failure();
        List<ParsedBank> banks = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String path : bankFiles.keySet()) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Asset> assets = new LinkedHashMap<>();
            for (String file : bankFiles.get(path)) if (resources.containsKey(file)) assets.put(file.substring(path.length() + 1), resources.get(file).asset());
            Map<String, Object> bank = json(path + "/bank.json", INVALID_QUESTION_BANK, "题库「" + name + "」");
            used.addAll(BankStructureRules.validate(name, bank, assets, manifests, null));
            banks.add(new ParsedBank(path + "/", name, bank, assets));
        }
        if (scope.equals("bank") && !used.equals(extensionFiles.keySet())) reject(INVALID_LIBRARY_ARCHIVE, "单题库包只能携带该题库绑定的拓展");
        List<ParsedExtension> extensions = new ArrayList<>();
        for (String path : extensionFiles.keySet()) {
            if (extensionFailures.containsKey(path)) throw extensionFailures.get(path);
            Map<String, Object> manifest = manifests.get(path);
            Map<String, byte[]> content = extensionBytes.get(path);
            LegacyExtensionRules.validate(path, manifest, content);
            String example = LegacyExtensionRules.relative((String) manifest.get("examples"));
            String parent = example.substring(0, example.lastIndexOf('/') + 1);
            Map<String, Asset> exampleAssets = new LinkedHashMap<>();
            for (var file : content.entrySet()) if (file.getKey().startsWith(parent + "assets/") && file.getKey().substring(parent.length()).split("/", -1).length == 2) {
                try {
                    ResourceType type = ResourceType.detect(file.getKey(), file.getValue());
                    exampleAssets.put(file.getKey().substring(parent.length()), new Asset(hash(file.getValue()), file.getValue().length, type));
                } catch (PackageRejectedException rejected) { if (rejected.code() == LIBRARY_ARCHIVE_LIMIT) throw rejected; }
            }
            Map<String, Object> exampleBank = json(path + "/" + LegacyExtensionRules.relative(example), INVALID_EXTENSION, "拓展「" + path + "」示例");
            try { BankStructureRules.validate(path + "/" + example, exampleBank, exampleAssets, manifests, path); }
            catch (PackageRejectedException rejected) { throw new PackageRejectedException(INVALID_EXTENSION, rejected.getMessage()); }
            if (content.size() > 2000 || content.values().stream().mapToLong(bytes -> bytes.length).sum() > 64L * 1024 * 1024) reject(LIBRARY_ARCHIVE_LIMIT, "拓展超过审核摘要的文件数量或总大小上限：" + path);
            String digest;
            try { digest = ExtensionDigest.compute(content); }
            catch (IllegalArgumentException error) { reject(INVALID_EXTENSION, "拓展审核摘要无法计算：" + path); return null; }
            Map<String, String> hashes = new LinkedHashMap<>();
            for (var file : content.entrySet()) hashes.put(file.getKey(), store(file.getValue()));
            extensions.add(new ParsedExtension(path, manifest, hashes, digest));
        }
        return new ParsedPackage(new PackageManifest(group, scope), banks, extensions);
    }

    private void layout(String group, Map<String, List<String>> banks, Map<String, List<String>> extensions) {
        if (duplicate) reject(INVALID_LIBRARY_ARCHIVE, "包内文件路径重复（不区分大小写）");
        for (String path : files.keySet()) {
            if (path.equals(MARKER)) continue;
            String[] parts = path.split("/", -1);
            for (String part : parts) if (Set.of(".instance.json", ".bundle.json", "development.json").contains(part)) reject(INVALID_LIBRARY_ARCHIVE, "包内不允许宿主文件：" + path);
            if (parts.length < 4 || !parts[1].equals(group)) reject(INVALID_LIBRARY_ARCHIVE, "题库和拓展必须在同名的唯一分组下：" + path);
            for (int index = path.indexOf('/'); index >= 0; index = path.indexOf('/', index + 1)) if (files.containsKey(path.substring(0, index))) reject(INVALID_LIBRARY_ARCHIVE, "文件与目录路径冲突：" + path);
            String leaf = String.join("/", parts[0], parts[1], parts[2]);
            if (parts[0].equals("question-banks")) {
                if (!(parts.length == 4 && parts[3].equals("bank.json")) && !(parts.length == 5 && parts[3].equals("assets"))) reject(INVALID_LIBRARY_ARCHIVE, "题库目录只允许 bank.json 和直接位于 assets/ 的资源文件：" + path);
                banks.computeIfAbsent(leaf, ignored -> new ArrayList<>()).add(path);
            } else if (parts[0].equals("extensions")) extensions.computeIfAbsent(leaf, ignored -> new ArrayList<>()).add(path);
            else reject(INVALID_LIBRARY_ARCHIVE, "包内不允许该路径：" + path);
        }
    }

    private Map<String, Object> json(String path, PackageRejectedException.Code code, String context) {
        FileBytes file = files.get(path);
        if (file == null) { reject(code, context + "：缺少文件 " + path); return null; }
        byte[] bytes = requireBytes(path);
        try { return object(PackageJson.read(bytes), failure(code, context)); }
        catch (PackageRejectedException error) { throw error; }
        catch (RuntimeException error) { reject(code, context + "：必须为有效的 UTF-8 JSON 对象"); return null; }
    }
    private byte[] requireBytes(String path) {
        FileBytes file = files.get(path);
        if (file.bytes() == null) reject(LIBRARY_ARCHIVE_LIMIT, "文件超过大小上限：" + path);
        return file.bytes();
    }
    private String store(byte[] bytes) {
        String expected = hash(bytes);
        try {
            String stored = sink.store(bytes);
            if (!expected.equals(stored)) throw new IllegalStateException("文件存储返回的 SHA-256 不一致");
            return stored;
        } catch (IOException error) { throw new UncheckedIOException("无法写入内容存储", error); }
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean metadata(String path) {
        String[] parts = path.split("/", -1);
        return path.equals(MARKER) || parts.length == 4 && parts[0].equals("extensions") && Set.of("manifest.json", "editor.json").contains(parts[3]);
    }
    private static boolean privatePath(String path) {
        for (String part : path.split("/")) if (part.startsWith(".") || part.equals("node_modules") || part.equals("target")) return true;
        return false;
    }
    private static void folder(String name, java.util.function.Consumer<String> fail) {
        if (name.startsWith(".") || name.endsWith(" ") || name.endsWith(".") || name.matches(".*[\\\\/:*?\"<>|\\x00-\\x1f\\x7f-\\x9f].*")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?") || Set.of("bank.json", "manifest.json", "development.json").contains(name.toLowerCase(Locale.ROOT))) fail.accept("分组名称无效");
    }
    private static java.util.function.Consumer<String> failure(PackageRejectedException.Code code, String context) {
        return detail -> reject(code, context + "：" + detail);
    }
    private static void reject(PackageRejectedException.Code code, String message) { throw new PackageRejectedException(code, message); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableObject(Map<String, Object> value) { return (Map<String, Object>) immutable(value); }
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put((String) key, immutable(child)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(child -> copy.add(immutable(child)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
