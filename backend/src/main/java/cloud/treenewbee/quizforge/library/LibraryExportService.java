package cloud.treenewbee.quizforge.library;

import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LibraryExportService {
    private static final Logger LOG = LoggerFactory.getLogger(LibraryExportService.class);
    private static final long DEFAULT_LIMIT = 128L * 1024 * 1024;
    private final LibraryMapper library;
    private final LibraryExportMapper mapper;
    private final BlobStore blobs;
    private final JsonMapper json;
    private final Path directory;
    private final Clock clock;
    private final long limit;

    public LibraryExportService(LibraryMapper library, LibraryExportMapper mapper, BlobStore blobs, JsonMapper json,
            Clock clock, @Value("${qf.storage.blob-dir}") String root,
            @Value("${qf.library.export-max-bytes:134217728}") long limit) {
        this.library = library;
        this.mapper = mapper;
        this.blobs = blobs;
        this.json = json;
        this.clock = clock;
        this.directory = Path.of(root).resolve("exports");
        this.limit = Math.min(limit, DEFAULT_LIMIT);
        if (this.limit < 1) throw new IllegalArgumentException("导出上限必须为正数");
    }

    public record Request(String bank, String group) {}
    public record Download(Path path, String fileName, List<String> warnings) {}
    private record Bank(LibraryExportMapper.Document source, Map<String, Object> document, Map<String, String> bindings) {}

    public Download export(String user, Request request) {
        if (request == null || (request.bank() == null) == (request.group() == null)) {
            throw LibraryNames.invalid("须选择单本题库或一个分组");
        }
        List<String> ids;
        if (request.bank() != null) ids = List.of(request.bank());
        else {
            String group = LibraryNames.group(request.group());
            if (group == null || !library.groups(user).contains(group)) throw LibraryService.notFound();
            ids = library.items(user).stream().filter(item -> group.equals(item.groupName())).map(LibraryMapper.Item::id).toList();
        }
        if (ids.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_GROUP", "空分组中没有可导出的题库");
        List<String> warnings = new ArrayList<>();
        Set<String> uuids = new HashSet<>();
        for (String id : ids) {
            var source = mapper.document(user, id).orElseThrow(LibraryService::notFound);
            Bank bank = source.authorJson() == null ? reconstruct(source, warnings) :
                    new Bank(source, object(source.authorJson()), stringMap(source.authorBindings()));
            if (!uuids.add((String) bank.document().get("id"))) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_BANK_UUID", "分组含有相同 UUID 的题库实例，请逐本导出");
            }
        }
        String group = folder(request.group() == null ? mapper.document(user, ids.getFirst()).orElseThrow(LibraryService::notFound).groupName() : request.group(), "导出题库");
        Map<String, String> extensionPaths = new LinkedHashMap<>();
        Set<String> extensionNames = new HashSet<>();
        for (String id : ids) {
            Bank bank = loadBank(user, id, warnings);
            for (String path : boundPaths(bank.document())) {
                String digest = bank.bindings().get(path);
                if (digest == null) throw invalid("缺少拓展绑定，无法重建此题库包");
                if (extensionPaths.containsKey(digest)) continue;
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!extensionNames.add(name.toLowerCase(Locale.ROOT))) name = "拓展-" + digest;
                extensionPaths.put(digest, "extensions/" + group + "/" + name);
            }
        }
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "building-", ".tmp");
            try (var raw = new LimitedOutputStream(Files.newOutputStream(temporary), limit);
                    var zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                var writer = new ArchiveWriter(zip);
                writer.json("quizforge-package.json", Map.of("format", "quizforge-bank-group", "formatVersion", 4, "group", group, "scope", request.bank() != null ? "bank" : "group"));
                Set<String> names = new HashSet<>();
                for (String id : ids) {
                    Bank bank = loadBank(user, id, warnings);
                    String name = folder(bank.source().title(), folder(bank.source().sourceTitle(), "题库"));
                    String base = name;
                    for (int number = 2; !names.add(name.toLowerCase(Locale.ROOT)); number++) name = base.substring(0, Math.min(base.length(), 100)) + "（" + number + "）";
                    String prefix = "question-banks/" + group + "/" + name + "/";
                    rebind(bank.document(), bank.bindings(), extensionPaths);
                    writer.json(prefix + "bank.json", bank.document());
                    for (var asset : mapper.assets(bank.source().bankId())) {
                        try (var input = blobs.open(hex(asset.blobSha256()))) { writer.copy(prefix + asset.path(), input); }
                    }
                }
                for (var extension : extensionPaths.entrySet()) {
                    String digest = extension.getKey(), path = extension.getValue();
                    Map<String, Object> manifest = object(mapper.extension(bytes(digest)).orElseThrow(() -> invalid("缺少拓展包")).manifest());
                    Set<String> examples = examplePaths(manifest.get("examples"));
                    for (var file : mapper.files(bytes(digest))) {
                        try (var input = blobs.open(hex(file.blobSha256()))) {
                            if (examples.contains(file.path())) {
                                byte[] original = input.readNBytes(8 * 1024 * 1024 + 1);
                                if (original.length > 8 * 1024 * 1024) throw tooLarge();
                                Map<String, Object> example = object(new String(original, StandardCharsets.UTF_8));
                                // 示例只能绑定自己的拓展，改写必需的包内路径，其余字段保持作者值。
                                Map<String, String> own = new HashMap<>();
                                boundPaths(example).forEach(old -> own.put(old, digest));
                                rebind(example, own, extensionPaths);
                                byte[] rewritten = json.writeValueAsBytes(example);
                                writer.copy(path + "/" + file.path(), new ByteArrayInputStream(rewritten));
                                if (!json.readTree(original).equals(json.readTree(rewritten))) {
                                    warnings.add("拓展示例绑定路径已改写，重新导入后需重新审核");
                                }
                            } else writer.copy(path + "/" + file.path(), input);
                        }
                    }
                }
            }
            validateArchive(temporary);
            try {
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 本机测试使用 ACL；Linux 容器需允许 Nginx 的不同 UID 读取。
            }
            Path published = directory.resolve(UUID.randomUUID() + ".qbank");
            Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return new Download(published, group + ".qbank", warnings.stream().distinct().toList());
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "无法生成题库包，请稍后重试");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException failure) { LOG.warn("导出临时文件清理失败", failure); }
        }
    }

    private static void validateArchive(Path path) throws IOException {
        var parser = new cloud.treenewbee.quizforge.content.domain.QbankPackageParser(data -> {
            try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)); }
            catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        });
        try {
            cloud.treenewbee.quizforge.content.archive.QbankArchiveReader.read(path,
                    cloud.treenewbee.quizforge.content.archive.ArchiveLimits.DEFAULT, parser);
            parser.finish();
        } catch (cloud.treenewbee.quizforge.content.domain.PackageRejectedException failure) {
            throw invalid("无法生成可重新导入的题库包：" + failure.getMessage());
        } catch (cloud.treenewbee.quizforge.content.archive.ArchiveRejectedException failure) {
            if (failure.reason() == cloud.treenewbee.quizforge.content.archive.ArchiveRejectedException.Reason.LIMIT) throw tooLarge();
            throw invalid("无法生成可重新导入的题库包：" + failure.getMessage());
        }
    }

    private Bank loadBank(String user, String id, List<String> warnings) {
        var source = mapper.document(user, id).orElseThrow(LibraryService::notFound);
        return source.authorJson() == null ? reconstruct(source, warnings) :
                new Bank(source, object(source.authorJson()), stringMap(source.authorBindings()));
    }

    @SuppressWarnings("unchecked")
    private Bank reconstruct(LibraryExportMapper.Document source, List<String> warnings) {
        warnings.add("旧数据未保存原始 bank.json，已按现存内容重建；作者 UUID 稳定派生，原先未引用题目无法恢复");
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("formatVersion", 2);
        bank.put("id", UUID.nameUUIDFromBytes(("qf-legacy-bank:" + source.bankId()).getBytes(StandardCharsets.UTF_8)).toString());
        if (source.description() != null) bank.put("description", source.description());
        if (source.features() != null) bank.put("features", object(source.features()));
        if (source.listening() != null) bank.put("listening", object(source.listening()));
        List<Map<String, Object>> questions = new ArrayList<>();
        List<Map<String, Object>> outline = new ArrayList<>();
        Map<String, String> bindings = new LinkedHashMap<>();
        Integer previousGroup = null;
        List<Object> items = null;
        for (var row : mapper.questions(source.bankId())) {
            String digest = hex(row.extensionDigest()), path = "extensions/旧数据/" + digest;
            bindings.put(path, digest);
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("id", row.questionId());
            question.put("title", row.title());
            question.put("extension", new LinkedHashMap<>(Map.of("path", path)));
            question.put("data", json.readValue(row.data(), Object.class));
            if (row.listening() != null) question.put("listening", object(row.listening()));
            questions.add(question);
            if (items == null || !Objects.equals(previousGroup, row.outlineGroupIndex())) {
                Map<String, Object> group = new LinkedHashMap<>();
                if (row.outlineGroupName() != null) group.put("group", row.outlineGroupName());
                items = new ArrayList<>();
                group.put("items", items);
                outline.add(group);
                previousGroup = row.outlineGroupIndex();
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", row.questionId());
            if (row.outline() != null) {
                Map<String, Object> saved = object(row.outline());
                if ("parts".equals(saved.get("level"))) item.put("parts", saved.get("items"));
                else if (saved.get("label") != null) item.put("label", saved.get("label"));
            }
            items.add(item);
        }
        bank.put("questions", questions);
        bank.put("outline", outline);
        return new Bank(source, bank, bindings);
    }

    @SuppressWarnings("unchecked")
    private static List<String> boundPaths(Map<String, Object> bank) {
        List<String> paths = new ArrayList<>();
        if (bank.get("extension") instanceof Map<?, ?> binding) paths.add((String) binding.get("path"));
        if (bank.get("questions") instanceof List<?> questions) for (Object raw : questions) {
            if (((Map<?, ?>) raw).get("extension") instanceof Map<?, ?> binding) paths.add((String) binding.get("path"));
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static void rebind(Map<String, Object> bank, Map<String, String> bindings, Map<String, String> paths) {
        List<Map<String, Object>> owners = new ArrayList<>();
        owners.add(bank);
        owners.addAll((List<Map<String, Object>>) bank.getOrDefault("questions", List.of()));
        for (var owner : owners) if (owner.get("extension") instanceof Map<?, ?> raw) {
            var binding = (Map<String, Object>) raw;
            String replacement = paths.get(bindings.get(binding.get("path")));
            if (replacement == null) throw invalid("无法恢复题目的拓展路径");
            binding.put("path", replacement);
        }
    }

    private static Set<String> examplePaths(Object raw) {
        Set<String> result = new HashSet<>();
        if (raw instanceof String path) result.add(path);
        else if (raw instanceof List<?> list) for (Object item : list) {
            if (item instanceof String path) result.add(path);
            else if (item instanceof Map<?, ?> entry && entry.get("file") instanceof String path) result.add(path);
        }
        return result;
    }

    private static String folder(String candidate, String fallback) {
        try {
            String value = LibraryNames.group(candidate);
            return value == null ? fallback : value;
        } catch (ApiException ignored) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(String value) { return json.readValue(value, LinkedHashMap.class); }
    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(String value) { return json.readValue(value, LinkedHashMap.class); }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static byte[] bytes(String value) { return HexFormat.of().parseHex(value); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_UNAVAILABLE", message); }
    private static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "LIBRARY_ARCHIVE_LIMIT", "导出结果超过题库包上限，请改为逐本导出"); }

    /** 文件交给 Nginx 后保留一小时，避免响应返回时过早删除；仅清理本模块的文件。 */
    @Scheduled(fixedDelayString = "${qf.library.export-cleanup-ms:600000}")
    public void cleanup() {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            var paths = files.filter(path -> path.getFileName().toString().matches("(?:[0-9a-f-]{36}\\.qbank|building-.*\\.tmp)")).iterator();
            int removed = 0;
            while (paths.hasNext() && removed < 100) {
                Path path = paths.next();
                if (Files.getLastModifiedTime(path).toInstant().isBefore(clock.instant().minus(Duration.ofHours(1)))) {
                    if (Files.deleteIfExists(path)) removed++;
                }
            }
        } catch (IOException failure) { LOG.warn("导出过期文件清理失败", failure); }
    }

    private final class ArchiveWriter {
        private final ZipOutputStream zip;
        private long expanded;
        private int entries;
        private ArchiveWriter(ZipOutputStream zip) { this.zip = zip; }
        void json(String path, Object value) throws IOException {
            byte[] data = json.writeValueAsBytes(value);
            if (data.length > (path.endsWith("bank.json") ? 8 * 1024 * 1024 : 256 * 1024)) throw tooLarge();
            copy(path, new ByteArrayInputStream(data));
        }
        void copy(String path, InputStream input) throws IOException {
            if (++entries > 10000) throw tooLarge();
            if (path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/", -1)).stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) throw invalid("导出文件路径无效");
            zip.putNextEntry(new ZipEntry(path));
            byte[] buffer = new byte[8192];
            int count;
            long entrySize = 0;
            while ((count = input.read(buffer)) != -1) {
                expanded += count;
                entrySize += count;
                if (expanded > 256L * 1024 * 1024 || entrySize > 32L * 1024 * 1024) throw tooLarge();
                zip.write(buffer, 0, count);
            }
            zip.closeEntry();
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long count;
        LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (++count > limit) throw tooLarge();
            out.write(value);
        }
        @Override public void write(byte[] bytes, int start, int length) throws IOException {
            count += length;
            if (count > limit) throw tooLarge();
            out.write(bytes, start, length);
        }
    }
}
