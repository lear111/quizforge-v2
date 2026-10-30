package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageFixture.*;
import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.INVALID_QUESTION_BANK;
import static cloud.treenewbee.quizforge.content.domain.QbankPackageParserTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import cloud.treenewbee.quizforge.content.archive.ArchiveLimits;
import cloud.treenewbee.quizforge.content.archive.QbankArchiveReader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BankUuidTest {
    static Stream<Arguments> invalidIds() {
        Map<String, Consumer<Map<String, Object>>> cases = new java.util.LinkedHashMap<>();
        cases.put("missing", bank -> bank.remove("id"));
        cases.put("null", bank -> bank.put("id", null));
        cases.put("number", bank -> bank.put("id", 123));
        cases.put("object", bank -> bank.put("id", Map.of()));
        for (String id : List.of("", " ", "examples", "author.old-id",
                "60000000-0000-4000-8000-00000000000g", "60000000-0000-4000-8000-00000000000A",
                "60000000000040008000000000000001", "6-0-4-8-1",
                "60000000-0000-4000-8000-000000000001 ", " 60000000-0000-4000-8000-000000000001",
                "{60000000-0000-4000-8000-000000000001}", "60000000-0000-4000-8000-000000000001\n")) {
            cases.put("invalid: " + id, bank -> bank.put("id", id));
        }
        return cases.entrySet().stream().flatMap(entry -> Stream.of(false, true)
                .map(example -> Arguments.of(entry.getKey(), example, entry.getValue())));
    }

    @ParameterizedTest(name = "{0}, example={1}")
    @MethodSource("invalidIds")
    void requiresCanonicalLowercaseUuidForBanksAndExamples(String name, boolean example,
            Consumer<Map<String, Object>> change) throws Exception {
        PackageFixture fixture = fixture();
        String path = example ? fixture.extension + "/examples/bank.json" : BANK + "/bank.json";
        fixture.edit(path, change);
        assertThat(rejected(fixture, INVALID_QUESTION_BANK).getMessage()).contains(path, "id", "UUID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-000000000000", "abcdef01-2345-6789-abcd-ef0123456789"})
    void preservesCanonicalUuidWithoutImposingVersionOrVariantRestrictions(String id) throws Exception {
        PackageFixture fixture = fixture();
        fixture.bank(bank -> bank.put("id", id));
        assertThat(fixture.parse().banks().getFirst().bank()).containsEntry("id", id);
        // Author identity is unique within a package, and may recur in another import.
        assertThat(fixture.parse().banks().getFirst().bank()).containsEntry("id", id);
    }

    @Test
    void rejectsDuplicateUuidBetweenOrdinaryBanksRegardlessOfFolderName() throws Exception {
        PackageFixture fixture = fixture();
        fixture.files.put("question-banks/" + GROUP + "/改名/bank.json", fixture.files.get(BANK + "/bank.json"));
        assertThat(rejected(fixture, INVALID_QUESTION_BANK).getMessage()).contains("改名/bank.json", "重复");
    }

    @Test
    void rejectsDuplicateUuidBetweenBankAndExtensionExample() throws Exception {
        PackageFixture fixture = fixture();
        fixture.edit(fixture.extension + "/examples/bank.json", bank -> bank.put("id", ImportArchives.BANK_IDS.get("单选题")));
        assertThat(rejected(fixture, INVALID_QUESTION_BANK).getMessage()).contains("examples/bank.json", "重复");
    }

    @Test
    void rejectsDuplicateUuidBetweenDifferentExtensionExamples() throws Exception {
        PackageFixture fixture = fixture();
        fixture.addExtension("判断题");
        String path = "extensions/" + GROUP + "/判断题/examples/bank.json";
        fixture.edit(path, bank -> bank.put("id", ImportArchives.EXAMPLE_IDS.get("单选题")));
        assertThat(rejected(fixture, INVALID_QUESTION_BANK).getMessage()).contains(path, "重复");
    }

    @Test
    void groupAndSingleBankFixturesKeepTheSameFixedIdentities() throws Exception {
        var group = ImportArchives.files(ImportArchives.TYPES, "group");
        var single = ImportArchives.files(List.of("单选题"), "bank");
        assertIdentities(group);
        assertIdentities(single);
        assertThat(single.get(ImportArchives.bank("单选题") + "/bank.json"))
                .isEqualTo(group.get(ImportArchives.bank("单选题") + "/bank.json"));
        QbankPackageParser parser = new QbankPackageParser(QbankPackageParser::hash);
        group.forEach(parser::accept);
        assertThat(parser.finish().banks()).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic-types.qbank", "single-choice.qbank"})
    void committedSamplesCarryFixedUniqueUuidsAndParseSuccessfully(String filename) throws Exception {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        Path archive = Path.of(System.getProperty("basedir", ".")).resolve("../samples").resolve(filename);
        QbankArchiveReader.read(archive, ArchiveLimits.DEFAULT, files::put);
        assertIdentities(files);
        QbankPackageParser parser = new QbankPackageParser(QbankPackageParser::hash);
        files.forEach(parser::accept);
        assertThat(parser.finish().banks()).hasSize(filename.equals("basic-types.qbank") ? 5 : 1);
    }

    private static void assertIdentities(Map<String, byte[]> files) {
        HashSet<Object> ids = new HashSet<>();
        for (String type : ImportArchives.TYPES) {
            String bank = ImportArchives.bank(type) + "/bank.json";
            if (!files.containsKey(bank)) continue;
            Object id = document(files.get(bank)).get("id");
            Object exampleId = document(files.get(ImportArchives.extension(type) + "/examples/bank.json")).get("id");
            assertThat(id).isEqualTo(ImportArchives.BANK_IDS.get(type));
            assertThat(exampleId).isEqualTo(ImportArchives.EXAMPLE_IDS.get(type));
            assertThat(ids.add(id)).isTrue();
            assertThat(ids.add(exampleId)).isTrue();
        }
    }
}
