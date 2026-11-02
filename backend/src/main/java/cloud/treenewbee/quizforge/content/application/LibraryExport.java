package cloud.treenewbee.quizforge.content.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.content.infrastructure.ExportMapper;
import cloud.treenewbee.quizforge.content.infrastructure.ExportMapper.ExportBank;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 从已存储的内容重新打包 .qbank（设计 04 第 6.1、6.4 节）：每本题库写出导入时保存的 bank.json 与资源，
 * 附上它们绑定的拓展文件，分组名取自绑定路径 extensions/&lt;组&gt;/&lt;拓展&gt;。重新导入后内容哈希不变，
 * 所以共享内容直接复用。题库来自不同分组、目录重名或同一路径绑定了不同拓展时，无法放进一个包，直接拒绝。
 */
@Service
public class LibraryExport {

    private static final long ENTRY_TIME = 1_704_067_200_000L; // 2024-01-01T00:00:00Z：同样的内容打出同样的字节

    private final ExportMapper mapper;
    private final BlobStore blobs;
    private final JsonMapper json;
    private final Path temporaryDirectory;

    public LibraryExport(ExportMapper mapper, BlobStore blobs, JsonMapper json,
                         @Value("${qf.import.temp-dir:${java.io.tmpdir}}") String temporaryDirectory) {
        this.mapper = mapper;
        this.blobs = blobs;
        this.json = json;
        this.temporaryDirectory = Path.of(temporaryDirectory);
    }

    /** 打包这些共享内容（content_bank.id），返回临时文件；调用方负责删除。 */
    public Path pack(List<String> bankIds) {
        if (bankIds.isEmpty()) throw conflict("没有选择题库");
        String group = null;
        Map<String, String> folders = new LinkedHashMap<>();        // 题库目录名 → bank id
        Map<String, ExportBank> banks = new LinkedHashMap<>();
        Map<String, byte[]> bankJson = new LinkedHashMap<>();
        Map<String, String> extensionDigests = new TreeMap<>();     // 拓展目录（extensions/组/名）→ 摘要
        Set<String> uuids = new HashSet<>();
        try {
            for (String id : bankIds) {
                ExportBank bank = mapper.bank(id);
                if (bank == null) throw conflict("题库内容不存在");
                if (bank.bankJsonSha256() == null || bank.bankUuid() == null) {
                    throw conflict("题库「" + (bank.sourceTitle() == null ? id : bank.sourceTitle())
                            + "」是在支持分享之前导入的，请重新导入后再分享");
                }
                if (!uuids.add(bank.bankUuid())) throw conflict("所选题库中有重复的题库");
                if (folders.putIfAbsent(bank.sourceTitle(), id) != null) {
                    throw conflict("所选题库中有同名的题库「" + bank.sourceTitle() + "」，不能放进同一个题库包");
                }
                byte[] text;
                try (InputStream input = blobs.open(HexFormat.of().formatHex(bank.bankJsonSha256()))) { text = input.readAllBytes(); }
                JsonNode root = json.readTree(text);
                String fallback = root.path("extension").path("path").asString(null);
                Map<String, String> byQuestion = new LinkedHashMap<>();
                for (JsonNode question : root.path("questions")) {
                    byQuestion.put(question.path("id").asString(), question.path("extension").path("path").asString(fallback));
                }
                for (var question : mapper.questions(id)) {
                    String path = byQuestion.get(question.questionId());
                    if (path == null) throw conflict("题库「" + bank.sourceTitle() + "」的拓展绑定无法还原");
                    String[] parts = path.split("/");
                    if (parts.length != 3 || !parts[0].equals("extensions")) throw conflict("题库「" + bank.sourceTitle() + "」的拓展绑定无法还原");
                    if (group == null) group = parts[1];
                    else if (!group.equals(parts[1])) throw conflict("所选题库来自不同分组的题库包，请分别分享");
                    String digest = HexFormat.of().formatHex(question.extensionDigest());
                    String previous = extensionDigests.putIfAbsent(path, digest);
                    if (previous != null && !previous.equals(digest)) {
                        throw conflict("所选题库中同一个拓展目录「" + path + "」对应不同版本的拓展，请分别分享");
                    }
                }
                // bank.json 中出现的每个绑定（包括默认绑定和不在大纲中的题）都必须能还原到拓展文件。
                Set<String> bound = new HashSet<>(byQuestion.values());
                if (fallback != null) bound.add(fallback);
                bound.remove(null);
                if (!extensionDigests.keySet().containsAll(bound)) {
                    throw conflict("题库「" + bank.sourceTitle() + "」绑定的部分拓展没有题目使用，无法还原，请上传原 .qbank 文件分享");
                }
                banks.put(id, bank);
                bankJson.put(id, text);
            }
            Files.createDirectories(temporaryDirectory);
            Path archive = Files.createTempFile(temporaryDirectory, "qbank-export-", ".qbank");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
                Map<String, Object> marker = new LinkedHashMap<>();
                marker.put("format", "quizforge-bank-group");
                marker.put("formatVersion", 4);
                marker.put("group", group);
                entry(zip, "quizforge-package.json", json.writeValueAsBytes(marker));
                for (var bank : banks.values()) {
                    String directory = "question-banks/" + group + "/" + bank.sourceTitle() + "/";
                    entry(zip, directory + "bank.json", bankJson.get(bank.id()));
                    for (var asset : mapper.assets(bank.id())) copy(zip, directory + asset.path(), asset.blobSha256());
                }
                for (var extension : extensionDigests.entrySet()) {
                    for (var file : mapper.extensionFiles(HexFormat.of().parseHex(extension.getValue()))) {
                        copy(zip, extension.getKey() + "/" + file.path(), file.blobSha256());
                    }
                }
            } catch (IOException | RuntimeException error) {
                Files.deleteIfExists(archive);
                throw error;
            }
            return archive;
        } catch (IOException error) {
            throw new UncheckedIOException("无法打包题库", error);
        }
    }

    private void copy(ZipOutputStream zip, String path, byte[] sha256) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(ENTRY_TIME);
        zip.putNextEntry(entry);
        try (InputStream input = blobs.open(HexFormat.of().formatHex(sha256))) { input.transferTo((OutputStream) zip); }
        zip.closeEntry();
    }

    private static void entry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "LIBRARY_SHARE_UNAVAILABLE", message);
    }
}
