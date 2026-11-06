package cloud.treenewbee.quizforge.content.application;

import cloud.treenewbee.quizforge.admin.application.ExtensionReviewService;
import cloud.treenewbee.quizforge.content.archive.ArchiveLimits;
import cloud.treenewbee.quizforge.content.archive.QbankArchiveReader;
import cloud.treenewbee.quizforge.content.domain.BankContentHash;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedBank;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedExtension;
import cloud.treenewbee.quizforge.content.domain.QuestionContentHash;
import cloud.treenewbee.quizforge.content.infrastructure.*;
import cloud.treenewbee.quizforge.rules.RulePackage;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImportService {
    public static final long MAX_UPLOAD_BYTES = 128L * 1024 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private final BlobStore blobs;
    private final ImportRules rules;
    private final ExtensionPackageMapper packages;
    private final ExtensionFileMapper files;
    private final BankMapper banks;
    private final QuestionMapper questions;
    private final BankAssetMapper assets;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final Path temporaryDirectory;
    private final ExtensionReviewService reviews;
    private final QueuedImportValidation queuedValidation;

    public ImportService(BlobStore blobs, ImportRules rules, ExtensionPackageMapper packages, ExtensionFileMapper files,
            BankMapper banks, QuestionMapper questions, BankAssetMapper assets,
            TransactionTemplate transactions, JsonMapper json, Clock clock,
            @Value("${qf.import.temp-dir:${java.io.tmpdir}}") String temporaryDirectory,
            ExtensionReviewService reviews,
            QueuedImportValidation queuedValidation) {
        this.blobs = blobs; this.rules = rules; this.packages = packages; this.files = files;
        this.banks = banks; this.questions = questions; this.assets = assets;
        this.transactions = transactions; this.json = json; this.clock = clock;
        this.temporaryDirectory = Path.of(temporaryDirectory);
        this.reviews = reviews;
        this.queuedValidation = queuedValidation;
    }

    /** 导入后的一本题库：bankUuid 为 bank.json 中的 UUID（小写），bankId 为共享内容 content_bank.id。 */
    public record StoredBank(String bankUuid, String bankId, String title, String bankKey, int questionCount,
            long logicalBytes, List<String> omittedIds) {}
    /** 一次导入存储的结果；extensionDigests 为包内全部拓展的审核摘要（十六进制，排序）。 */
    public record Ingested(String group, List<StoredBank> banks, List<String> extensionDigests, Instant now) {}

    private record Extension(ParsedExtension parsed, RulePackage spec, boolean canOutline) {}
    private record Placement(int groupIndex, String groupName, Object outline) {}
    private record Question(Map<String, Object> author, Extension extension, Object outline, BigDecimal maxScore, String hash,
            int groupIndex, String groupName) {}
    private record Bank(ParsedBank parsed, String hash, Map<String, String> assetHashes, List<Question> questions, List<String> omittedIds, long logicalBytes) {}

    /** 把上传写入临时文件（不超过 128 MiB）；调用方负责用 {@link #delete(Path)} 删除。 */
    public Path spool(InputStream upload) {
        Path temporary = null;
        try {
            Files.createDirectories(temporaryDirectory);
            temporary = Files.createTempFile(temporaryDirectory, "qbank-upload-", ".qbank");
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; long count = 0; int read;
                while ((read = upload.read(buffer)) != -1) {
                    count += read;
                    if (count > MAX_UPLOAD_BYTES) throw new ImportFailure("LIBRARY_ARCHIVE_LIMIT", "题库包超过 128 MiB 上传上限");
                    output.write(buffer, 0, read);
                }
            }
            return temporary;
        } catch (IOException error) {
            delete(temporary);
            throw new UncheckedIOException("无法读取上传的题库包", error);
        } catch (RuntimeException error) {
            delete(temporary);
            throw error;
        }
    }

    public void delete(Path temporary) {
        if (temporary == null) return;
        try { Files.deleteIfExists(temporary); }
        catch (IOException cleanup) { LOG.warn("Unable to remove an import temporary file", cleanup); }
    }

    /** 队列跨题库归组，所有规则使用后台名额；提交前复核租约栅栏。 */
    public <T> T ingestQueued(Path archive, Runnable guard, Consumer<String> beforeStore, Function<Ingested, T> sameTransaction) {
        long started = System.nanoTime();
        QbankPackageParser parser = new QbankPackageParser(blobs::put);
        Map<String, Long> fileSizes = new LinkedHashMap<>();
        QbankArchiveReader.read(archive, ArchiveLimits.DEFAULT, (path, bytes) -> {
            parser.accept(path, bytes); fileSizes.put(path, (long) bytes.length);
        });
        var parsed = parser.finish(); // Includes C-04 digests and immutable author JSON.
        long parsedAt = System.nanoTime();
        var validated = queuedValidation.validate(parsed.extensions(), parsed.banks(), guard);
        Map<String, Extension> extensions = new LinkedHashMap<>();
        for (ParsedExtension extension : parsed.extensions()) {
            for (String path : extension.files().keySet()) if (path.codePointCount(0, path.length()) > 255) {
                throw new ImportFailure("INVALID_EXTENSION", "拓展文件路径超过存储上限：" + extension.path());
            }
            RulePackage spec = rules.spec(extension);
            JsonNode capabilities = validated.get(extension.path()).capabilities();
            if (!capabilities.path("canAiGrade").isBoolean() || !capabilities.path("canOutlineItems").isBoolean()) throw ImportRules.unavailable();
            extensions.put(extension.path(), new Extension(extension, spec, capabilities.path("canOutlineItems").asBoolean()));
        }
        List<Bank> prepared = parsed.banks().stream().map(bank -> prepare(bank, extensions, fileSizes, validated)).toList();
        long validatedAt = System.nanoTime();
        T result = transactions.execute(status -> {
            beforeStore.accept(parsed.manifest().group());
            return sameTransaction.apply(persist(parsed.manifest().group(), extensions, prepared));
        });
        LOG.info("题库包处理完成：parseMs={} validateMs={} storeMs={} banks={}",
                (parsedAt - started) / 1_000_000, (validatedAt - parsedAt) / 1_000_000,
                (System.nanoTime() - validatedAt) / 1_000_000, prepared.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Bank prepare(ParsedBank bank, Map<String, Extension> extensions, Map<String, Long> fileSizes,
            Map<String, QueuedImportValidation.Validated> validated) {
        Map<String, String> assetHashes = new LinkedHashMap<>();
        bank.assets().forEach((path, asset) -> {
            if (path.codePointCount(0, path.length()) > 255) throw new ImportFailure("INVALID_QUESTION_BANK", "题库「" + bank.folderName() + "」资源路径超过存储上限");
            assetHashes.put(path.substring("assets/".length()), asset.sha256());
        });
        List<Map<String, Object>> authorQuestions = (List<Map<String, Object>>) bank.bank().get("questions");
        Map<String, Placement> placements = new LinkedHashMap<>();
        List<Map<String, Object>> outlineGroups = (List<Map<String, Object>>) bank.bank().get("outline");
        for (int g = 0; g < outlineGroups.size(); g++) {
            Map<String, Object> group = outlineGroups.get(g);
            for (Map<String, Object> item : (List<Map<String, Object>>) group.get("items")) {
                Object outline = item.containsKey("label") ? Map.of("level", "question", "label", item.get("label"))
                        : item.containsKey("parts") ? Map.of("level", "parts", "items", item.get("parts")) : null;
                placements.put((String) item.get("question"), new Placement(g, (String) group.get("group"), outline));
            }
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        List<Extension> bindings = new ArrayList<>();
        for (int index = 0; index < authorQuestions.size(); index++) {
            Map<String, Object> question = authorQuestions.get(index);
            Map<String, Object> binding = (Map<String, Object>) question.getOrDefault("extension", bank.bank().get("extension"));
            String path = (String) binding.get("path");
            bindings.add(extensions.get(path));
            groups.computeIfAbsent(path, ignored -> new ArrayList<>()).add(index);
        }
        List<Question> prepared = new ArrayList<>(java.util.Collections.nCopies(authorQuestions.size(), null));
        for (var group : groups.entrySet()) {
            Extension extension = extensions.get(group.getKey());
            List<Object> data = group.getValue().stream().map(i -> authorQuestions.get(i).get("data")).toList();
            var outcomes = validated.get(group.getKey()).questions();
            List<Integer> missingOutline = group.getValue().stream().filter(i -> {
                Placement placement = placements.get(authorQuestions.get(i).get("id"));
                return placement == null || placement.outline() == null;
            }).toList();
            Map<Integer, Object> outlines = new HashMap<>();
            if (extension.canOutline() && extension.spec().outlineItemsDeclared() && !missingOutline.isEmpty()) {
                List<JsonNode> generated = missingOutline.stream().map(i -> outcomes.get(cloud.treenewbee.quizforge.content.domain.RuleInputHash
                                .compute(authorQuestions.get(i).get("data"))).path("outlineItems")).toList();
                for (int i = 0; i < missingOutline.size(); i++) {
                    if (!generated.get(i).isArray()) throw ImportRules.unavailable();
                    JsonNode items = generated.get(i);
                    if (!items.isEmpty()) {
                        outlines.put(missingOutline.get(i), Map.of("level", "parts", "items", json.readValue(items.toString(), Object.class)));
                    }
                }
            }
            List<JsonNode> scores = data.stream().map(value -> outcomes.get(cloud.treenewbee.quizforge.content.domain.RuleInputHash.compute(value))
                    .path("score")).toList();
            for (int i = 0; i < group.getValue().size(); i++) {
                int index = group.getValue().get(i);
                Map<String, Object> author = authorQuestions.get(index);
                Placement placement = placements.get(author.get("id"));
                Object outline = placement != null && placement.outline() != null ? placement.outline() : outlines.get(index);
                if (outline instanceof JsonNode node) outline = json.readValue(node.toString(), Object.class);
                prepared.set(index, new Question(author, extension, outline, maxScore(scores.get(i).path("maxScore")),
                        QuestionContentHash.compute(author, extension.parsed().digest(), outline, bank.bank().get("listening"), assetHashes),
                        placement == null ? -1 : placement.groupIndex(), placement == null ? null : placement.groupName()));
            }
        }
        String hash = BankContentHash.compute(bank.bank(), assetHashes, bindings.stream().map(e -> e.parsed().digest()).toList());
        Map<String, Question> byId = new LinkedHashMap<>();
        prepared.forEach(question -> byId.put((String) question.author().get("id"), question));
        List<String> omitted = byId.keySet().stream().filter(id -> !placements.containsKey(id)).toList();
        var boundPaths = new java.util.HashSet<String>(groups.keySet());
        if (bank.bank().get("extension") instanceof Map<?, ?> fallback) boundPaths.add((String) fallback.get("path"));
        long logicalBytes = fileSizes.getOrDefault("quizforge-package.json", 0L);
        for (var file : fileSizes.entrySet()) {
            if (file.getKey().startsWith(bank.bankKey()) || boundPaths.stream().anyMatch(path -> file.getKey().startsWith(path + "/")))
                logicalBytes = Math.addExact(logicalBytes, file.getValue());
        }
        return new Bank(bank, hash, Map.copyOf(assetHashes), placements.keySet().stream().map(byId::get).toList(), omitted, logicalBytes);
    }

    private BigDecimal maxScore(JsonNode value) {
        try {
            if (!value.isNumber()) throw new ArithmeticException();
            BigDecimal score = new BigDecimal(value.toString()).setScale(4, RoundingMode.UNNECESSARY);
            if (score.signum() < 0 || score.precision() > 12) throw new ArithmeticException();
            return score;
        } catch (NumberFormatException | ArithmeticException error) {
            throw new ImportFailure("SCORE_UNAVAILABLE", "题目满分不能精确存入 DECIMAL(12,4)");
        }
    }

    private Ingested persist(String group, Map<String, Extension> extensions, List<Bank> prepared) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        // All shared keys are acquired in a consistent order, including partially overlapping packages.
        for (Extension extension : extensions.values().stream().sorted(Comparator.comparing(e -> e.parsed().digest())).toList()) {
            var parsed = extension.parsed(); var manifest = parsed.manifest();
            packages.insertIfAbsent(new ExtensionPackageRow(hex(parsed.digest()), (String) manifest.get("id"),
                    (String) manifest.get("version"), (String) manifest.get("name"), encode(manifest),
                    extension.spec().apiVersion().major(), extension.spec().apiVersion().minor(), now));
            for (var file : parsed.files().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                files.insertIfAbsent(new ExtensionFileRow(hex(parsed.digest()), file.getKey(), hex(file.getValue())));
            }
            // 未知的拓展摘要自动提交审核（路线图 L3.1）；已有记录（任何状态）不变。
            reviews.registerPending(parsed.digest(), (String) manifest.get("id"), (String) manifest.get("version"));
        }
        Map<String, String> ids = new HashMap<>();
        for (Bank bank : prepared.stream().sorted(Comparator.comparing(Bank::hash)).toList()) {
            String id = Ids.uuidV7();
            boolean created = true;
            Map<String, Object> author = bank.parsed().bank();
            String uuid = bankUuid(author);
            try {
                banks.insert(new BankRow(id, hex(bank.hash()), uuid, bank.parsed().folderName(),
                        (String) author.get("description"), 2, encode(bank.parsed().features()), nullableJson(author.get("listening")),
                        bank.questions().size(), now));
            } catch (DuplicateKeyException conflict) {
                id = banks.findIdByContentSha256ForShare(hex(bank.hash())).orElseThrow(() -> conflict);
                created = false;
                banks.fillIdentity(id, uuid);
            }
            ids.put(bank.hash(), id);
            if (!created) continue;
            List<QuestionRow> rows = new ArrayList<>();
            for (int index = 0; index < bank.questions().size(); index++) {
                Question question = bank.questions().get(index);
                String digest = question.extension().parsed().digest();
                rows.add(new QuestionRow(id, (String) question.author().get("id"), index, (String) question.author().get("title"),
                        hex(digest), nullableJson(question.outline()), nullableJson(question.author().get("listening")), encode(question.author().get("data")),
                        question.maxScore(), hex(question.hash()), question.groupIndex(), question.groupName()));
            }
            questions.insertBatch(rows);
            if (!bank.parsed().assets().isEmpty()) {
                List<BankAssetRow> assetRows = new ArrayList<>();
                for (var asset : bank.parsed().assets().entrySet()) {
                    assetRows.add(new BankAssetRow(id, asset.getKey(), hex(asset.getValue().sha256())));
                }
                assets.insertBatch(assetRows);
            }
        }
        List<StoredBank> stored = prepared.stream().map(bank -> new StoredBank(bankUuid(bank.parsed().bank()),
                ids.get(bank.hash()), bank.parsed().folderName(), bank.parsed().bankKey(), bank.questions().size(),
                bank.logicalBytes(), bank.omittedIds())).toList();
        List<String> digests = extensions.values().stream().map(e -> e.parsed().digest()).distinct().sorted().toList();
        return new Ingested(group, stored, digests, now);
    }

    private static String bankUuid(Map<String, Object> bank) {
        return ((String) bank.get("id")).toLowerCase(Locale.ROOT);
    }

    private String nullableJson(Object value) { return value == null ? null : encode(value); }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private static byte[] hex(String hash) { return HexFormat.of().parseHex(hash); }
}
