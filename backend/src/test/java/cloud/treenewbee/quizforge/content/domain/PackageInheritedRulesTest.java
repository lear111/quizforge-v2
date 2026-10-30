package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageFixture.*;
import static cloud.treenewbee.quizforge.content.domain.QbankPackageParserTest.*;
import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PackageInheritedRulesTest {
    static Stream<Arguments> outlines() {
        return Stream.of(outline("bad level", Map.of("level", "other", "label", "1")),
                outline("missing label", Map.of("level", "question")), outline("extra field", Map.of("level", "question", "label", "1", "items", List.of())),
                outline("empty label", Map.of("level", "question", "label", "")), outline("long label", Map.of("level", "question", "label", "x".repeat(81))),
                outline("edge NBSP", Map.of("level", "question", "label", "\u00a01")), outline("HTML label", Map.of("level", "question", "label", "<b>")),
                outline("control label", Map.of("level", "question", "label", "a\u0085b")), outline("empty parts", Map.of("level", "parts", "items", List.of())),
                outline("duplicate parts", Map.of("level", "parts", "items", List.of(Map.of("id", "a", "label", "1"), Map.of("id", "a", "label", "2")))),
                outline("long part ID", Map.of("level", "parts", "items", List.of(Map.of("id", "x".repeat(129), "label", "1")))),
                outline("extra part field", Map.of("level", "parts", "items", List.of(Map.of("id", "a", "label", "1", "other", true)))));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("outlines")
    void appendixOutlineRejectsInvalidShapePlainTextLengthAndIds(String description, Object outline) throws Exception {
        PackageFixture f = fixture(); f.question(q -> q.put("outline", outline));
        assertThat(rejected(f, INVALID_QUESTION_BANK).getMessage()).contains("练习", "q1");
    }
    @Test
    void outlinePartsRequireCapabilityAndAcceptExactLengthAndCountBoundaries() throws Exception {
        PackageFixture f = fixture();
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) items.add(Map.of("id", "x".repeat(126) + i, "label", "标".repeat(80)));
        f.question(q -> q.put("outline", Map.of("level", "parts", "items", items)));
        rejected(f, INVALID_QUESTION_BANK);
        f.manifest(m -> m.put("requiresApi", Map.of("major", 1, "minMinor", 1, "capabilities", List.of("outline-items"))));
        assertThat(f.parse().banks()).hasSize(1);
        items.add(Map.of("id", "last", "label", "101"));
        f.question(q -> q.put("outline", Map.of("level", "parts", "items", items))); rejected(f, INVALID_QUESTION_BANK);
    }

    static Stream<Arguments> listeningFailures() {
        return Stream.of(listening("empty", b -> b.put("listening", Map.of("materials", List.of()))),
                listening("unknown", b -> b.put("listening", Map.of("materials", List.of(material("one", "assets/a.mp3")), "extra", 1))),
                listening("bad id", b -> b.put("listening", Map.of("materials", List.of(material("bad.id", "assets/a.mp3"))))),
                listening("long id", b -> b.put("listening", Map.of("materials", List.of(material("x".repeat(81), "assets/a.mp3"))))),
                listening("blank title", b -> b.put("listening", Map.of("materials", List.of(Map.of("id", "one", "title", " ", "file", "assets/a.mp3"))))),
                listening("long title", b -> b.put("listening", Map.of("materials", List.of(Map.of("id", "one", "title", "x".repeat(161), "file", "assets/a.mp3"))))),
                listening("missing audio", b -> b.put("listening", Map.of("materials", List.of(material("one", "assets/missing.mp3"))))),
                listening("image is not audio", b -> b.put("listening", Map.of("materials", List.of(material("one", "assets/自由图.PNG"))))),
                listening("old media", b -> b.put("listening", Map.of("materials", List.of(material("one", "media/a.mp3"))))),
                listening("URL", b -> b.put("listening", Map.of("materials", List.of(material("one", "https://example/a.mp3"))))),
                listening("duplicate", b -> b.put("listening", Map.of("materials", List.of(material("one", "assets/a.mp3"), material("one", "assets/a.mp3"))))),
                listening("bad exam", b -> b.put("listening", Map.of("materials", List.of(material("one", "assets/a.mp3")), "examMaterialId", "missing"))));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("listeningFailures")
    void sectionFourListeningRejectsMalformedMaterialsAndReferences(String description, Consumer<Map<String, Object>> change) throws Exception {
        PackageFixture f = audioFixture(); f.bank(change); rejected(f, INVALID_QUESTION_BANK);
    }
    @Test
    void listeningFiftyBoundaryAndQuestionBindingAndAudioLargerThanEightMiBAreAllowed() throws Exception {
        PackageFixture f = audioFixture();
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(Map.of("id", "m" + i, "title", "t".repeat(160), "file", "assets/a.mp3"));
        f.bank(b -> b.put("listening", Map.of("materials", rows, "examMaterialId", "m49")));
        f.question(q -> q.put("listening", Map.of("materialId", "m0")));
        f.files.put(BANK + "/assets/a.mp3", Arrays.copyOf(new byte[]{'I', 'D', '3'}, 9 * 1024 * 1024));
        assertThat(f.parse().banks().getFirst().assets().get("assets/a.mp3").type()).isEqualTo(ResourceType.AUDIO);
        f.question(q -> q.put("listening", Map.of("materialId", "missing"))); assertThat(rejected(f, INVALID_QUESTION_BANK).getMessage()).contains("q1");
        f.question(q -> q.remove("listening")); rows.add(material("m50", "assets/a.mp3"));
        f.bank(b -> b.put("listening", Map.of("materials", rows))); rejected(f, INVALID_QUESTION_BANK);
    }

    static Stream<Arguments> apiFailures() {
        return Stream.of(api("missing major", Map.of("minMinor", 0)), api("missing minor", Map.of("major", 1)),
                api("fractional major", Map.of("major", 1.0, "minMinor", 0)), api("negative minor", Map.of("major", 1, "minMinor", -1)),
                api("future major", Map.of("major", 2, "minMinor", 0)), api("future minor", Map.of("major", 1, "minMinor", 6)),
                api("int overflow", Map.of("major", 2147483648L, "minMinor", 0)), api("extra field", Map.of("major", 1, "minMinor", 0, "extra", 1)),
                api("unknown cap", Map.of("major", 1, "minMinor", 5, "capabilities", List.of("future"))),
                api("duplicate cap", Map.of("major", 1, "minMinor", 5, "capabilities", List.of("practice", "practice"))),
                api("empty cap", Map.of("major", 1, "minMinor", 5, "capabilities", List.of(""))),
                api("not array", Map.of("major", 1, "minMinor", 5, "capabilities", "practice")));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("apiFailures")
    void appendixRequiresApiRulesRejectUnsupportedOrMalformedDeclarations(String description, Object requirement) throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put("requiresApi", requirement)); rejected(f, INVALID_EXTENSION);
    }
    static Stream<Arguments> gates() {
        return Stream.of(Arguments.of("outline-items", 1), Arguments.of("answer-state", 3), Arguments.of("appearance", 4), Arguments.of("answer-areas", 5));
    }
    @ParameterizedTest @MethodSource("gates")
    void everyCapabilityVersionGateHasPositiveAndNegativeBoundary(String capability, int minimum) throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put("requiresApi", Map.of("major", 1, "minMinor", minimum, "capabilities", List.of(capability))));
        assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> m.put("requiresApi", Map.of("major", 1, "minMinor", minimum - 1, "capabilities", List.of(capability)))); rejected(f, INVALID_EXTENSION);
    }
    @ParameterizedTest
    @ValueSource(strings = {"practice", "editor", "editor-drafts", "score", "manual-review", "ai-grading", "resources", "richtext", "navigation", "lifecycle", "outline-items", "answer-state", "appearance", "answer-areas"})
    void everySupportedApiCapabilityIsAccepted(String capability) throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put("requiresApi", Map.of("major", 1, "minMinor", 5, "capabilities", List.of(capability))));
        assertThat(f.parse().extensions()).hasSize(1);
    }
    @Test
    void omittedRequiresApiAndEmptyCapabilitiesRetainLegacyDefaults() throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.remove("requiresApi")); assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> m.put("requiresApi", Map.of("major", 1, "minMinor", 0, "capabilities", List.of()))); assertThat(f.parse().extensions()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "version", "name", "entry", "script", "style", "rules", "questionSchema", "answerSchema", "examples"})
    void everyRequiredManifestFieldIsRequired(String field) throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.remove(field)); rejected(f, INVALID_EXTENSION);
    }
    @Test
    void appendixManifestMetadataAndPathLengthBoundaries() throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> { m.put("id", "a".repeat(120)); m.put("version", "v".repeat(120)); m.put("name", "名".repeat(300)); m.put("description", "d".repeat(4000)); });
        assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> m.put("id", "a".repeat(121))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> m.put("name", "x".repeat(301))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> m.put("description", "x".repeat(4001))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> m.put("rules", "x".repeat(241))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> m.put("rules", "../outside.js")); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> m.put("rules", "./rules.js")); assertThat(f.parse().extensions()).hasSize(1);
    }
    @ParameterizedTest
    @ValueSource(strings = {"types", "packageFormatVersion", "unknown"})
    void formatFourManifestWhitelistRejectsUnknownAndRetiredFields(String field) throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put(field, true)); rejected(f, INVALID_EXTENSION);
    }
    @Test
    void examplesMustBeFormatTwoAndBindTheirExactOwner() throws Exception {
        PackageFixture f = fixture(); f.edit(f.extension + "/examples/bank.json", b -> b.put("formatVersion", 1)); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.addExtension("判断题"); f.edit(f.extension + "/examples/bank.json", b -> b.put("extension", Map.of("path", "extensions/基础题型/判断题"))); rejected(f, INVALID_EXTENSION);
    }

    @Test
    void richTextAndLegacyDependenciesUseOnlyExistingProfilesAndCapabilities() throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put("requiresRichText", Map.of("major", 1, "minMinor", 1, "documentFormat", 1, "documentProfile", "basic-v1", "capabilities", List.of("images"))));
        assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> object(m.get("requiresRichText")).put("capabilities", List.of("tables"))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> { m.remove("requiresRichText"); m.put("dependencies", List.of(Map.of("id", "quizforge.richtext", "version", "1.1.3"))); });
        assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> m.put("requiresRichText", Map.of())); rejected(f, INVALID_EXTENSION);
    }
    @Test
    void staticAssetsDeclarationsRetainLegacyDeduplicationAndCountRules() throws Exception {
        PackageFixture f = fixture(); f.files.put(f.extension + "/assets/logo.svg", new byte[0]);
        f.manifest(m -> m.put("assets", List.of("assets/logo.svg", "assets/logo.svg"))); assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> m.put("assets", List.of("missing.png"))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.files.put(f.extension + "/assets/clip.mp3", new byte[]{'I', 'D', '3'}); f.manifest(m -> m.put("assets", List.of("assets/clip.mp3"))); rejected(f, INVALID_EXTENSION);
    }

    @Test
    void staticAssetsCountAndPathAndInlineTotalBoundaries() throws Exception {
        PackageFixture f = fixture(); List<String> paths = new ArrayList<>();
        for (int i = 0; i < 100; i++) { String path = "assets/a" + i + ".svg"; paths.add(path); f.files.put(f.extension + "/" + path, new byte[0]); }
        f.manifest(m -> m.put("assets", paths)); assertThat(f.parse().extensions()).hasSize(1);
        paths.add("assets/extra.svg"); f.files.put(f.extension + "/assets/extra.svg", new byte[0]);
        f.manifest(m -> m.put("assets", paths)); rejected(f, INVALID_EXTENSION);
        f = fixture(); String path = "x".repeat(236) + ".svg"; f.files.put(f.extension + "/" + path, new byte[0]);
        f.manifest(m -> m.put("assets", List.of(path))); assertThat(f.parse().extensions()).hasSize(1);
        f.manifest(m -> m.put("assets", List.of("x".repeat(237) + ".svg"))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.files.put(f.extension + "/assets/one.svg", new byte[8 * 1024 * 1024]);
        f.manifest(m -> m.put("assets", List.of("assets/one.svg"))); assertThat(f.parse().extensions()).hasSize(1);
        f.files.put(f.extension + "/assets/two.svg", new byte[1]);
        f.manifest(m -> m.put("assets", List.of("assets/one.svg", "assets/two.svg"))); rejected(f, INVALID_EXTENSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "jpeg", "webp", "gif", "svg", "woff", "woff2", "ttf", "otf"})
    void everyLegacyStaticAssetSuffixIsSupportedWithoutMediaDecoding(String suffix) throws Exception {
        PackageFixture f = fixture(); f.files.put(f.extension + "/static/a." + suffix, new byte[0]);
        f.manifest(m -> m.put("assets", List.of("static/a." + suffix))); assertThat(f.parse().extensions()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic-formatting", "images", "advanced-formatting", "tables", "math", "image-resize", "camera-capture"})
    void everySupportedRichTextCapabilityIsAccepted(String capability) throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put("requiresRichText", Map.of("major", 1, "minMinor", 1, "documentFormat", 1, "documentProfile", "advanced-v1", "capabilities", List.of(capability))));
        assertThat(f.parse().extensions()).hasSize(1);
    }

    @Test
    void malformedRichTextDeclarationsDependenciesAndEditorsAreRejected() throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> object(m.get("requiresRichText")).put("extra", true)); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> object(m.get("requiresRichText")).put("minMinor", 2)); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> object(m.get("requiresRichText")).put("documentFormat", 2)); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> object(m.get("requiresRichText")).put("documentProfile", "future")); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> object(m.get("requiresRichText")).put("capabilities", List.of("images", "images"))); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> { m.remove("requiresRichText"); m.put("dependencies", List.of(Map.of("id", "other", "version", "1.0.0"))); }); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.put(f.extension + "/editor.json", Map.of("entry", "practice.html", "script", "practice.js", "style", "practice.css"));
        assertThat(f.parse().extensions()).hasSize(1);
        f.edit(f.extension + "/editor.json", editor -> editor.put("entry", "missing")); rejected(f, INVALID_EXTENSION);
        f = fixture(); f.files.put(f.extension + "/practice.html", new byte[]{(byte) 255}); rejected(f, INVALID_EXTENSION);
    }

    @Test
    void questionAndBindingLengthBoundariesWithExampleUuid() throws Exception {
        PackageFixture f = fixture(); f.question(q -> { q.put("id", "q".repeat(120)); q.put("title", "题".repeat(300)); });
        assertThat(f.parse().banks()).hasSize(1);
        f.question(q -> q.put("title", "x".repeat(299) + "😀")); rejected(f, INVALID_QUESTION_BANK);
        f = fixture(); f.bank(b -> b.put("extension", Map.of("path", "x".repeat(1001)))); rejected(f, INVALID_QUESTION_BANK);
        f = fixture(); f.bank(b -> b.put("extension", Map.of("path", "extensions/基础题型/单选题", "version", "2.0.1"))); rejected(f, INVALID_QUESTION_BANK);
    }

    @Test
    void arrayCapsForApiCapabilitiesAndLegacyDependenciesAreEnforced() throws Exception {
        PackageFixture f = fixture(); f.manifest(m -> m.put("requiresApi", Map.of("major", 1, "minMinor", 5, "capabilities", java.util.Collections.nCopies(101, "practice"))));
        rejected(f, INVALID_EXTENSION);
        f = fixture(); f.manifest(m -> { m.remove("requiresRichText"); m.put("dependencies", java.util.Collections.nCopies(5, Map.of("id", "quizforge.richtext", "version", "1.0.0"))); });
        rejected(f, INVALID_EXTENSION);
    }

    @Test
    void extensionDigestFileCountBoundaryIsReportedAsLimit() throws Exception {
        PackageFixture f = fixture(); int original = (int) f.files.keySet().stream().filter(path -> path.startsWith(f.extension + "/")).count();
        for (int i = original; i < 2000; i++) f.files.put(f.extension + "/extra-" + i + ".bin", new byte[0]);
        assertThat(f.parse().extensions().getFirst().files()).hasSize(2000);
        f.files.put(f.extension + "/one-more.bin", new byte[0]); rejected(f, LIBRARY_ARCHIVE_LIMIT);
    }

    @Test
    void materialIdLengthAndQuestionListeningWhitelistAreChecked() throws Exception {
        PackageFixture f = audioFixture(); String id = "m".repeat(80);
        f.bank(b -> b.put("listening", Map.of("materials", List.of(material(id, "assets/a.mp3")))));
        f.question(q -> q.put("listening", Map.of("materialId", id))); assertThat(f.parse().banks()).hasSize(1);
        f.question(q -> q.put("listening", Map.of("materialId", id, "extra", true))); rejected(f, INVALID_QUESTION_BANK);
    }

    @Test
    void bankAndExtensionCountBoundariesFollowPhysicalFoldersNotAuthorIdentity() throws Exception {
        PackageFixture f = fixture(); byte[] bank = f.files.get(BANK + "/bank.json");
        for (int i = 1; i < 1000; i++) {
            var copy = document(bank);
            copy.put("id", "70000000-0000-4000-8000-%012d".formatted(i));
            f.put("question-banks/" + GROUP + "/b" + i + "/bank.json", copy);
        }
        assertThat(f.parse().banks()).hasSize(1000);
        f.files.put("question-banks/" + GROUP + "/extra/bank.json", bank); rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture();
        for (int i = 1; i < 200; i++) f.addExtension("type" + i);
        assertThat(f.parse().extensions()).hasSize(200);
        f.addExtension("extra"); rejected(f, INVALID_LIBRARY_ARCHIVE);
    }
    @Test
    void bankScopeRejectsExtraBankAndUnusedExtensionWhileGroupScopeAllowsThem() throws Exception {
        PackageFixture f = fixture(); f.addExtension("判断题"); assertThat(f.parse().extensions()).hasSize(2);
        f.marker(m -> m.put("scope", "bank")); rejected(f, INVALID_LIBRARY_ARCHIVE);
        f = fixture(); f.files.put("question-banks/" + GROUP + "/second/bank.json", f.files.get(BANK + "/bank.json"));
        f.marker(m -> m.put("scope", "bank")); rejected(f, INVALID_LIBRARY_ARCHIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quizforge-package.json", "manifest.json", "editor.json", "bank.json", "rules.js"})
    void sectionSevenExactFileLimitAndOneByteOverflow(String kind) throws Exception {
        PackageFixture f = fixture(); String path;
        if (kind.equals("quizforge-package.json")) path = kind;
        else if (kind.equals("bank.json")) path = BANK + "/bank.json";
        else path = f.extension + "/" + kind;
        if (kind.equals("editor.json")) f.put(path, Map.of("entry", "practice.html", "script", "practice.js", "style", "practice.css"));
        int limit = kind.equals("bank.json") || kind.equals("rules.js") ? 8 * 1024 * 1024 : 256 * 1024;
        byte[] padded = Arrays.copyOf(f.files.get(path), limit); Arrays.fill(padded, f.files.get(path).length, padded.length, (byte) ' ');
        f.files.put(path, padded); assertThat(f.parse().banks()).hasSize(1);
        f.files.put(path, Arrays.copyOf(padded, limit + 1)); rejected(f, LIBRARY_ARCHIVE_LIMIT);
    }
    private static PackageFixture audioFixture() throws Exception { PackageFixture f = fixture(); f.files.put(BANK + "/assets/a.mp3", new byte[]{'I', 'D', '3'}); return f; }
    private static Map<String, Object> material(String id, String file) { return Map.of("id", id, "title", "听力", "file", file); }
    private static Arguments outline(String name, Object value) { return Arguments.of(name, value); }
    private static Arguments api(String name, Object value) { return Arguments.of(name, value); }
    private static Arguments listening(String name, Consumer<Map<String, Object>> change) { return Arguments.of(name, change); }
}
