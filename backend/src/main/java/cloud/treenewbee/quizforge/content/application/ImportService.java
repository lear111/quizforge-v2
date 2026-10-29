package cloud.treenewbee.quizforge.content.application;

import cloud.treenewbee.quizforge.content.archive.ArchiveLimits;
import cloud.treenewbee.quizforge.content.archive.QbankArchiveReader;
import cloud.treenewbee.quizforge.content.domain.BankContentHash;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedBank;
import cloud.treenewbee.quizforge.content.domain.QbankPackageParser.ParsedExtension;
import cloud.treenewbee.quizforge.content.domain.QuestionContentHash;
import cloud.treenewbee.quizforge.content.infrastructure.*;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeCollectionMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeCollectionRow;
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
    private final PracticeCollectionMapper collections;
    private final BankAvailability availability;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final Path temporaryDirectory;

    public ImportService(BlobStore blobs, ImportRules rules, ExtensionPackageMapper packages, ExtensionFileMapper files,
            BankMapper banks, QuestionMapper questions, BankAssetMapper assets, PracticeCollectionMapper collections,
            BankAvailability availability, TransactionTemplate transactions, JsonMapper json, Clock clock,
            @Value("${qf.import.temp-dir:${java.io.tmpdir}}") String temporaryDirectory) {
        this.blobs = blobs; this.rules = rules; this.packages = packages; this.files = files;
        this.banks = banks; this.questions = questions; this.assets = assets; this.collections = collections;
        this.availability = availability; this.transactions = transactions; this.json = json; this.clock = clock;
        this.temporaryDirectory = Path.of(temporaryDirectory);
    }

    public record ImportedCollection(String id, String bankId, String title, String groupName, boolean available) {}
    private record Extension(ParsedExtension parsed, RulePackage spec, boolean canOutline) {}
    private record Question(Map<String, Object> author, Extension extension, Object outline, BigDecimal maxScore, String hash) {}
    private record Bank(ParsedBank parsed, String hash, Map<String, String> assetHashes, List<Question> questions) {}

    /** Caller owns the input stream. Parsing and all HTTP calls finish before the business transaction starts. */
    public List<ImportedCollection> importArchive(String userId, String filename, InputStream upload) {
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
            QbankPackageParser parser = new QbankPackageParser(blobs::put);
            QbankArchiveReader.read(temporary, ArchiveLimits.DEFAULT, parser);
            var parsed = parser.finish(); // Includes C-04 digests and immutable author JSON.
            Map<String, Extension> extensions = new LinkedHashMap<>();
            for (ParsedExtension extension : parsed.extensions()) {
                for (String path : extension.files().keySet()) if (path.codePointCount(0, path.length()) > 255) {
                    throw new ImportFailure("INVALID_EXTENSION", "拓展文件路径超过存储上限：" + extension.path());
                }
                RulePackage spec = rules.spec(extension);
                JsonNode capabilities = rules.call(spec, "capabilities", Map.of());
                if (!capabilities.path("canAiGrade").isBoolean() || !capabilities.path("canOutlineItems").isBoolean()) throw ImportRules.unavailable();
                extensions.put(extension.path(), new Extension(extension, spec, capabilities.path("canOutlineItems").asBoolean()));
            }
            List<Bank> prepared = parsed.banks().stream().map(bank -> prepare(bank, extensions)).toList();
            return transactions.execute(status -> persist(userId, filename, parsed.manifest().group(), extensions, prepared));
        } catch (IOException error) { throw new UncheckedIOException("无法读取上传的题库包", error); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { LOG.warn("Unable to remove an import temporary file", cleanup); }
        }
    }

    @SuppressWarnings("unchecked")
    private Bank prepare(ParsedBank bank, Map<String, Extension> extensions) {
        Map<String, String> assetHashes = new LinkedHashMap<>();
        bank.assets().forEach((path, asset) -> {
            if (path.codePointCount(0, path.length()) > 255) throw new ImportFailure("INVALID_QUESTION_BANK", "题库「" + bank.folderName() + "」资源路径超过存储上限");
            assetHashes.put(path.substring("assets/".length()), asset.sha256());
        });
        List<Map<String, Object>> authorQuestions = (List<Map<String, Object>>) bank.bank().get("questions");
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
            rules.batch(extension.spec(), "validateBank", data, null);
            List<Integer> missingOutline = group.getValue().stream().filter(i -> !authorQuestions.get(i).containsKey("outline")).toList();
            Map<Integer, Object> outlines = new HashMap<>();
            if (extension.canOutline() && extension.spec().outlineItemsDeclared() && !missingOutline.isEmpty()) {
                List<JsonNode> generated = rules.batch(extension.spec(), "outlineBatch",
                        missingOutline.stream().map(i -> authorQuestions.get(i).get("data")).toList(), "outlineItems");
                for (int i = 0; i < missingOutline.size(); i++) {
                    if (!generated.get(i).isArray()) throw ImportRules.unavailable();
                    JsonNode items = generated.get(i);
                    if (!items.isEmpty()) {
                        outlines.put(missingOutline.get(i), Map.of("level", "parts", "items", json.readValue(items.toString(), Object.class)));
                    }
                }
            }
            List<?> scoreInput = data.stream().map(value -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("data", value); item.put("state", Map.of("status", "unanswered", "submitted", false)); return item;
            }).toList();
            List<JsonNode> scores = rules.batch(extension.spec(), "scoreBatch", scoreInput, "scores");
            for (int i = 0; i < group.getValue().size(); i++) {
                int index = group.getValue().get(i);
                Map<String, Object> author = authorQuestions.get(index);
                Object outline = author.containsKey("outline") ? author.get("outline") : outlines.get(index);
                if (outline instanceof JsonNode node) outline = json.readValue(node.toString(), Object.class);
                prepared.set(index, new Question(author, extension, outline, maxScore(scores.get(i).path("maxScore")),
                        QuestionContentHash.compute(author, extension.parsed().digest(), outline, bank.bank().get("listening"), assetHashes)));
            }
        }
        String hash = BankContentHash.compute(bank.bank(), assetHashes, bindings.stream().map(e -> e.parsed().digest()).toList());
        return new Bank(bank, hash, Map.copyOf(assetHashes), List.copyOf(prepared));
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

    private List<ImportedCollection> persist(String userId, String filename, String group, Map<String, Extension> extensions, List<Bank> prepared) {
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
        }
        Map<String, String> ids = new HashMap<>();
        for (Bank bank : prepared.stream().sorted(Comparator.comparing(Bank::hash)).toList()) {
            String id = Ids.uuidV7();
            boolean created = true;
            Map<String, Object> author = bank.parsed().bank();
            try {
                banks.insert(new BankRow(id, hex(bank.hash()), bank.parsed().folderName(), (String) author.get("description"), 2,
                        encode(bank.parsed().features()), nullableJson(author.get("listening")), bank.questions().size(), now));
            } catch (DuplicateKeyException conflict) {
                id = banks.findIdByContentSha256ForShare(hex(bank.hash())).orElseThrow(() -> conflict);
                created = false;
            }
            ids.put(bank.hash(), id);
            if (!created) continue;
            List<QuestionRow> rows = new ArrayList<>();
            for (int index = 0; index < bank.questions().size(); index++) {
                Question question = bank.questions().get(index);
                String digest = question.extension().parsed().digest();
                rows.add(new QuestionRow(id, (String) question.author().get("id"), index, (String) question.author().get("title"),
                        hex(digest), nullableJson(question.outline()), nullableJson(question.author().get("listening")), encode(question.author().get("data")),
                        question.maxScore(), hex(question.hash())));
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
        List<ImportedCollection> result = new ArrayList<>();
        for (Bank bank : prepared) {
            String id = Ids.uuidV7(), bankId = ids.get(bank.hash()), title = bank.parsed().folderName();
            collections.insert(new PracticeCollectionRow(id, userId, bankId, title, group,
                    encode(Map.of("type", "upload", "filename", filename)), null, null, bank.parsed().bankKey(), null, "current", now, null));
            result.add(new ImportedCollection(id, bankId, title, group, availability.isAvailable(bankId)));
        }
        return List.copyOf(result);
    }

    private String nullableJson(Object value) { return value == null ? null : encode(value); }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private static byte[] hex(String hash) { return HexFormat.of().parseHex(hash); }
}
