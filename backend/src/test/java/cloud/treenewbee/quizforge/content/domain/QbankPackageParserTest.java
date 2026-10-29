package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageFixture.*;
import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.*;
import static org.assertj.core.api.Assertions.*;

import cloud.treenewbee.quizforge.content.archive.ArchiveLimits;
import cloud.treenewbee.quizforge.content.archive.QbankArchiveReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QbankPackageParserTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"单选题", "多选题", "判断题", "简答题", "简答题（答题区）"})
    void fiveRealExtensionFixturesProduceImmutableFormatFourGroupsAndRawFileHashes(String type) throws Exception {
        PackageFixture fixture = new PackageFixture(type);
        var result = fixture.parse();
        assertThat(result.manifest()).isEqualTo(new QbankPackageParser.PackageManifest(GROUP, "group"));
        var bank = result.banks().getFirst();
        assertThat(bank.bankKey()).isEqualTo(BANK + "/");
        assertThat(bank.folderName()).isEqualTo("练习");
        assertThat(bank.bank().get("title")).isEqualTo("作者元数据名称");
        assertThat(bank.features().values()).containsOnly(true);
        var extension = result.extensions().getFirst();
        Map<String, byte[]> raw = new LinkedHashMap<>();
        fixture.files.forEach((path, bytes) -> { if (path.startsWith(fixture.extension + "/")) raw.put(path.substring(fixture.extension.length() + 1), bytes); });
        assertThat(extension.digest()).isEqualTo(ExtensionDigest.compute(raw));
        extension.files().forEach((path, hash) -> assertThat(hash).isEqualTo(QbankPackageParser.hash(raw.get(path))));
        assertThatThrownBy(() -> result.banks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bank.bank().put("x", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> object(((List<?>) bank.bank().get("questions")).getFirst()).put("x", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bank.assets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> extension.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> extension.manifest().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void singleBankScopeAndMixedPerQuestionBindingAreSupported() throws Exception {
        PackageFixture f = fixture(); f.addExtension("判断题"); f.marker(raw -> raw.put("scope", "bank"));
        f.question(q -> q.put("extension", Map.of("path", "extensions/" + GROUP + "/判断题", "id", "quizforge.true-false")));
        // Read the fixture's actual author ID rather than assuming its spelling.
        String id = (String) document(f.files.get("extensions/" + GROUP + "/判断题/manifest.json")).get("id");
        f.question(q -> q.put("extension", Map.of("path", "extensions/" + GROUP + "/判断题", "id", id)));
        assertThat(f.parse().extensions()).hasSize(2);
    }

    @Test
    void noDefaultBindingIsNeededWhenEveryQuestionHasOneAndQuestionIdsAreCaseSensitive() throws Exception {
        PackageFixture f = fixture();
        f.bank(bank -> {
            bank.remove("extension");
            bank.put("questions", List.of(Map.of("id", "q", "title", "a", "extension", Map.of("path", f.extension), "data", Map.of()),
                    Map.of("id", "Q", "title", "b", "extension", Map.of("path", f.extension), "data", List.of(1, 2))));
        });
        assertThat(f.parse().banks().getFirst().bank().get("questions")).asList().hasSize(2);
    }

    @Test
    void assetBytesAreStoredImmediatelyNotRetainedAndFailureDoesNotRollBackBlobs() throws Exception {
        PackageFixture f = fixture(); QbankPackageParser parser = f.parser();
        byte[] input = PNG.clone();
        parser.accept(BANK + "/assets/original.png", input);
        String hash = QbankPackageParser.hash(input);
        assertThat(f.stored).containsKey(hash);
        input[0] = 0;
        f.files.forEach(parser::accept);
        var asset = parser.finish().banks().getFirst().assets().get("assets/original.png");
        assertThat(asset).isEqualTo(new QbankPackageParser.Asset(hash, PNG.length, ResourceType.IMAGE));
        assertThat(Arrays.stream(QbankPackageParser.Asset.class.getRecordComponents()).noneMatch(component -> component.getType() == byte[].class)).isTrue();
        PackageFixture broken = fixture(); broken.bank(bank -> bank.put("formatVersion", 1));
        rejected(broken, INVALID_QUESTION_BANK);
        assertThat(broken.stored).containsKey(QbankPackageParser.hash(PNG));
    }

    @Test
    void sameContentDifferentFreeNamesDeduplicatesByHashAndUnusedAssetsRemain() throws Exception {
        PackageFixture f = fixture(); f.files.put(BANK + "/assets/another.png", PNG.clone());
        var bank = f.parse().banks().getFirst();
        assertThat(bank.assets()).hasSize(2);
        assertThat(bank.assets().values().stream().map(QbankPackageParser.Asset::sha256).distinct()).hasSize(1);
    }

    @Test
    void emptyAssetsSubdirectoryIsIgnoredByFileEvents() throws Exception {
        PackageFixture f = fixture();
        assertThat(zipParse(f, List.of(BANK + "/assets/empty/" )).banks()).hasSize(1);
    }

    @Test
    void emptyExtraGroupsDoNotCountTowardsTheOneGroupRule() throws Exception {
        PackageFixture f = fixture();
        assertThat(zipParse(f, List.of("question-banks/另一个空组/", "extensions/另一个空组/空拓展/")).manifest().group()).isEqualTo(GROUP);
    }

    @Test
    void emptyZipIsRejectedByThePackageLayerForMissingMarker() throws Exception {
        PackageFixture f = fixture(); f.files.clear();
        Throwable error = catchThrowable(() -> zipParse(f, List.of()));
        assertThat(error).isInstanceOf(PackageRejectedException.class);
        assertThat(((PackageRejectedException) error).code()).isEqualTo(INVALID_LIBRARY_ARCHIVE);
    }

    static Stream<Arguments> markerFailures() {
        return Stream.of(
                change("wrong format", f -> f.marker(m -> m.put("format", "other")), INVALID_LIBRARY_ARCHIVE),
                change("missing marker", f -> f.files.remove("quizforge-package.json"), INVALID_LIBRARY_ARCHIVE),
                change("extra marker field", f -> f.marker(m -> m.put("extra", 1)), INVALID_LIBRARY_ARCHIVE),
                change("old version", f -> f.marker(m -> m.put("formatVersion", 3)), UNSUPPORTED_LIBRARY_ARCHIVE),
                change("missing version", f -> f.marker(m -> m.remove("formatVersion")), UNSUPPORTED_LIBRARY_ARCHIVE),
                change("floating version", f -> f.marker(m -> m.put("formatVersion", 4.0)), UNSUPPORTED_LIBRARY_ARCHIVE),
                change("unknown scope", f -> f.marker(m -> m.put("scope", "all")), INVALID_LIBRARY_ARCHIVE),
                change("null scope", f -> f.marker(m -> m.put("scope", null)), INVALID_LIBRARY_ARCHIVE),
                change("missing group", f -> f.marker(m -> m.remove("group")), INVALID_LIBRARY_ARCHIVE),
                change("wrong group", f -> f.marker(m -> m.put("group", "other")), INVALID_LIBRARY_ARCHIVE),
                change("too long group", f -> f.marker(m -> m.put("group", "x".repeat(121))), INVALID_LIBRARY_ARCHIVE),
                change("blank group", f -> f.marker(m -> m.put("group", "\u00a0")), INVALID_LIBRARY_ARCHIVE),
                change("invalid JSON", f -> f.files.put("quizforge-package.json", new byte[]{'{'}), INVALID_LIBRARY_ARCHIVE));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("markerFailures")
    void sectionThreeMarkerRules(String name, Consumer<PackageFixture> change, PackageRejectedException.Code code) throws Exception {
        PackageFixture f = fixture(); change.accept(f); PackageRejectedException error = rejected(f, code);
        if (name.equals("old version")) assertThat(error).hasMessage("旧版题库包，请用新版客户端重新导出");
    }

    @ParameterizedTest
    @ValueSource(strings = {"arbitrary.txt", "question-banks/other/bank/bank.json", "extensions/other/type/manifest.json",
            "question-banks/基础题型/standalone.json", BANK + "/other.txt", BANK + "/media/audio.mp3", BANK + "/assets/nested/a.png",
            BANK + "/.instance.json", BANK + "/.bundle.json", "extensions/基础题型/单选题/development.json",
            "extensions/基础题型/单选题/.secret", "extensions/基础题型/单选题/node_modules/private.js", "extensions/基础题型/单选题/target/private.js"})
    void sectionThreeLayoutRejectsForbiddenFilesAndNonemptyExtraGroups(String path) throws Exception {
        PackageFixture f = fixture(); f.files.put(path, PNG.clone()); rejected(f, INVALID_LIBRARY_ARCHIVE);
    }

    @Test
    void missingBankOrExtensionManifestAndCaseDuplicateAreInvalidLayout() throws Exception {
        PackageFixture f = fixture(); f.files.remove(BANK + "/bank.json"); rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture(); f.files.remove(f.extension + "/manifest.json"); rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture(); f.files.put(BANK + "/assets/自由图.png", PNG.clone()); rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture(); f.files.keySet().removeIf(path -> path.startsWith("question-banks/")); rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture(); f.files.keySet().removeIf(path -> path.startsWith("extensions/")); rejected(f, INVALID_LIBRARY_ARCHIVE);
    }

    @Test
    void privateFilesMayBeDeclaredButHostIdentityFilesNeverMay() throws Exception {
        PackageFixture f = fixture(); f.files.put(f.extension + "/.private/.rules.js", f.files.remove(f.extension + "/rules.js"));
        f.manifest(m -> m.put("rules", ".private/.rules.js"));
        assertThat(f.parse().extensions().getFirst().files()).containsKey(".private/.rules.js");
        f = fixture(); f.files.put(f.extension + "/development.json", new byte[0]);
        f.manifest(m -> m.put("rules", "development.json")); rejected(f, INVALID_LIBRARY_ARCHIVE);
    }

    static Stream<Arguments> bankFailures() {
        return Stream.of(
                bankChange("missing version", b -> b.remove("formatVersion")), bankChange("old version", b -> b.put("formatVersion", 1)),
                bankChange("unknown root field", b -> b.put("unknown", true)), bankChange("bad description", b -> b.put("description", 1)),
                bankChange("long description", b -> b.put("description", "x".repeat(4001))), bankChange("empty questions", b -> b.put("questions", List.of())),
                bankChange("invalid author ID", b -> b.put("id", Map.of("opaque", true))),
                bankChange("long author ID", b -> b.put("id", "x".repeat(121))),
                bankChange("long author title", b -> b.put("title", "x".repeat(301))),
                bankChange("missing default binding", b -> b.remove("extension")), bankChange("legacy binding", b -> b.put("extension", Map.of("id", "old", "version", "1"))),
                bankChange("outside binding", b -> b.put("extension", Map.of("path", "extensions/other/missing"))),
                bankChange("wrong binding ID", b -> b.put("extension", Map.of("path", "extensions/基础题型/单选题", "id", "wrong"))),
                bankChange("unknown feature", b -> b.put("features", Map.of("unknown", true))), bankChange("nonboolean feature", b -> b.put("features", Map.of("exam", 0))),
                bankChange("null features", b -> b.put("features", null)));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("bankFailures")
    void sectionFourBankRules(String name, Consumer<Map<String, Object>> change) throws Exception {
        PackageFixture f = fixture(); f.bank(change); assertThat(rejected(f, INVALID_QUESTION_BANK).getMessage()).contains("练习");
    }

    static Stream<Arguments> questionFailures() {
        return Stream.of(questionChange("missing ID", q -> q.remove("id")), questionChange("invalid ID", q -> q.put("id", "汉字")),
                questionChange("long ID", q -> q.put("id", "x".repeat(121))), questionChange("blank title", q -> q.put("title", "\u00a0")),
                questionChange("long title", q -> q.put("title", "x".repeat(301))), questionChange("missing data", q -> q.remove("data")),
                questionChange("question features", q -> q.put("features", Map.of("exam", true))), questionChange("unknown field", q -> q.put("unknown", 1)),
                questionChange("bad binding", q -> q.put("extension", Map.of("path", "extensions/missing/type"))));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("questionFailures")
    void sectionFourQuestionRulesIncludeBankAndQuestionInMessages(String name, Consumer<Map<String, Object>> change) throws Exception {
        PackageFixture f = fixture(); f.question(change);
        assertThat(rejected(f, INVALID_QUESTION_BANK).getMessage()).contains("练习", "题目");
    }

    @Test
    void omittedAuthorMetadataAndOptionalTitleArePreservedWithoutInventedLimits() throws Exception {
        PackageFixture f = fixture();
        f.bank(b -> { b.remove("title"); b.put("id", "author.old-id"); b.put("version", "v".repeat(5000)); b.put("description", ""); b.put("features", Map.of("history", false)); });
        var bank = f.parse().banks().getFirst();
        assertThat(bank.bank()).doesNotContainKey("title");
        assertThat(bank.bank().get("version")).isEqualTo("v".repeat(5000));
        assertThat(bank.features()).containsEntry("history", false).containsEntry("exam", true);
    }

    @Test
    void duplicateQuestionIdsAreRejectedAndQuestionMaximumBoundariesAreChecked() throws Exception {
        PackageFixture f = fixture(); f.bank(b -> { var q = ((List<?>) b.get("questions")).getFirst(); b.put("questions", List.of(q, q)); });
        rejected(f, INVALID_QUESTION_BANK);
        f = fixture();
        List<Object> questions = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) questions.add(Map.of("id", "q" + i, "title", "题", "data", Map.of()));
        f.bank(b -> b.put("questions", questions)); assertThat(f.parse().banks()).hasSize(1);
        questions.add(Map.of("id", "q10000", "title", "题", "data", Map.of()));
        f.bank(b -> b.put("questions", questions)); rejected(f, INVALID_QUESTION_BANK);
    }

    @Test
    void recursiveImageReferencesUseContentHashNotNameAndCannotUseAnotherBanksOrAudioAssets() throws Exception {
        PackageFixture f = fixture(); String hash = QbankPackageParser.hash(PNG);
        f.question(q -> q.put("data", Map.of("deep", List.of(Map.of("type", "image", "attrs", Map.of("assetId", hash))))));
        assertThat(f.parse().banks()).hasSize(1);
        f.files.remove(BANK + "/assets/自由图.PNG");
        f.files.put("question-banks/" + GROUP + "/另一本/assets/图.png", PNG.clone());
        f.put("question-banks/" + GROUP + "/另一本/bank.json", Map.of("formatVersion", 2, "extension", Map.of("path", f.extension), "questions", List.of(Map.of("id", "q", "title", "题", "data", Map.of()))));
        assertThat(rejected(f, INVALID_QUESTION_BANK).getMessage()).contains("练习", "q1", "引用了不存在的图片");
    }

    @Test
    void errorOrderingKeepsMarkerThenDirectoryAheadOfResourceFailures() throws Exception {
        PackageFixture f = fixture(); f.files.put(BANK + "/assets/bad.exe", PNG.clone()); f.marker(m -> m.put("formatVersion", 3));
        rejected(f, UNSUPPORTED_LIBRARY_ARCHIVE);
        f = fixture(); f.files.put(BANK + "/assets/bad.exe", PNG.clone()); f.files.put(BANK + "/illegal.txt", new byte[0]);
        rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture(); f.files.put(BANK + "/assets/bad.exe", PNG.clone()); f.files.put(f.extension + "/manifest.json", new byte[]{'{'});
        rejected(f, UNSUPPORTED_RESOURCE_TYPE);
        f = fixture(); f.bank(b -> b.put("formatVersion", 1)); f.files.put(f.extension + "/manifest.json", new byte[]{'{'});
        rejected(f, INVALID_QUESTION_BANK);
    }

    @Test
    void malformedImageHashAndAudioContentCannotSatisfyImageReference() throws Exception {
        PackageFixture f = fixture();
        f.question(q -> q.put("data", Map.of("type", "image", "attrs", Map.of("assetId", "BAD")))); rejected(f, INVALID_QUESTION_BANK);
        byte[] audio = {'I', 'D', '3'}; String hash = QbankPackageParser.hash(audio);
        f.files.put(BANK + "/assets/audio.mp3", audio);
        f.question(q -> q.put("data", Map.of("type", "image", "attrs", Map.of("assetId", hash)))); rejected(f, INVALID_QUESTION_BANK);
    }

    @Test
    void perQuestionDataMayBeNullAndNonImageAssetIdKeysStayOpaque() throws Exception {
        PackageFixture f = fixture(); f.question(q -> q.put("data", null)); assertThat(f.parse().banks()).hasSize(1);
        f.question(q -> q.put("data", Map.of("type", "private", "attrs", Map.of("assetId", "not-an-image")))); assertThat(f.parse().banks()).hasSize(1);
    }

    @Test
    void unsupportedResourceAndStorageFaultsStayDistinctAndParserIsSingleUse() throws Exception {
        PackageFixture f = fixture(); f.files.put(BANK + "/assets/bad.exe", PNG.clone()); rejected(f, UNSUPPORTED_RESOURCE_TYPE);
        IOException fault = new IOException("disk failure"); QbankPackageParser parser = new QbankPackageParser(bytes -> { throw fault; });
        assertThatThrownBy(() -> parser.accept(BANK + "/assets/a.png", PNG)).isInstanceOf(UncheckedIOException.class).hasCause(fault);
        f = fixture(); QbankPackageParser used = f.parser(); f.files.forEach(used::accept); used.finish();
        assertThatThrownBy(used::finish).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> used.accept("x", new byte[0])).isInstanceOf(IllegalStateException.class);
    }

    private QbankPackageParser.ParsedPackage zipParse(PackageFixture f, List<String> emptyDirectories) throws Exception {
        Path zip = temporary.resolve("package.qbank");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String directory : emptyDirectories) { out.putNextEntry(new ZipEntry(directory)); out.closeEntry(); }
            for (var file : f.files.entrySet()) { out.putNextEntry(new ZipEntry(file.getKey())); out.write(file.getValue()); out.closeEntry(); }
        }
        QbankPackageParser parser = f.parser(); QbankArchiveReader.read(zip, ArchiveLimits.DEFAULT, parser); return parser.finish();
    }
    static PackageFixture fixture() throws IOException { return new PackageFixture("单选题"); }
    static PackageRejectedException rejected(PackageFixture f, PackageRejectedException.Code code) {
        Throwable error = catchThrowable(f::parse); assertThat(error).isInstanceOf(PackageRejectedException.class);
        assertThat(((PackageRejectedException) error).code()).as(error.getMessage()).isEqualTo(code); return (PackageRejectedException) error;
    }
    private static Arguments change(String name, Consumer<PackageFixture> change, PackageRejectedException.Code code) { return Arguments.of(name, change, code); }
    private static Arguments bankChange(String name, Consumer<Map<String, Object>> change) { return Arguments.of(name, change); }
    private static Arguments questionChange(String name, Consumer<Map<String, Object>> change) { return Arguments.of(name, change); }
}
