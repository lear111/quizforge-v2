package cloud.treenewbee.quizforge.content.archive;

import static cloud.treenewbee.quizforge.content.archive.ArchiveRejectedException.Reason.INVALID;
import static cloud.treenewbee.quizforge.content.archive.ArchiveRejectedException.Reason.LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unzip behavior reference: quizforge_web 4522bb8, library-archive.js and library-archive.test.mjs. */
class QbankArchiveReaderTest {
    private static final ArchiveLimits DEFAULT = ArchiveLimits.DEFAULT;
    @TempDir Path temporary;
    private int sequence;

    @Test
    void normalPackageDeliversEveryFileInArchiveOrderWithExactBytesAndChinesePaths() throws Exception {
        List<String> names = List.of("quizforge-package.json", "question-banks/", "question-banks/组/题库/",
                "question-banks/组/题库/bank.json", "question-banks/组/题库/assets/图片.png",
                "extensions/组/拓展/", "extensions/组/拓展/manifest.json", "extensions/组/拓展/rules.js");
        List<byte[]> contents = List.of(utf8("{\"format\":\"quizforge-bank-group\",\"formatVersion\":3}"),
                new byte[0], new byte[0], utf8("{\"title\":\"中文题库 😀\"}"), new byte[] {0, -1, 7, 42},
                new byte[0], utf8("{\"id\":\"测试\"}"), utf8("// 规则"));
        Path zip = temporary.resolve("normal.qbank");
        // Default DEFLATED entries use data descriptors, as real streaming ZIP writers do.
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (int i = 0; i < names.size(); i++) {
                stream.putNextEntry(new ZipEntry(names.get(i))); stream.write(contents.get(i)); stream.closeEntry();
            }
        }
        List<String> delivered = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        QbankArchiveReader.read(zip, DEFAULT, (name, content) -> { delivered.add(name); bytes.add(content); });
        assertThat(delivered).containsExactly(names.get(0), names.get(3), names.get(4), names.get(6), names.get(7));
        for (int i = 0; i < delivered.size(); i++) assertThat(bytes.get(i)).containsExactly(contents.get(names.indexOf(delivered.get(i))));
    }

    static Stream<Arguments> validPaths() {
        return Stream.of(
                Arguments.of("quizforge-package.json", false, "quizforge-package.json"),
                Arguments.of("question-banks/", true, "question-banks"),
                Arguments.of("extensions/", true, "extensions"),
                Arguments.of("question-banks", false, "question-banks"),
                Arguments.of("extensions", false, "extensions"),
                Arguments.of("question-banks/组/题库/bank.json", false, "question-banks/组/题库/bank.json"),
                Arguments.of("extensions/组/拓展/", true, "extensions/组/拓展"),
                Arguments.of("extensions/.rules.js", false, "extensions/.rules.js"),
                Arguments.of("extensions/ space-prefix/x..json", false, "extensions/ space-prefix/x..json"),
                Arguments.of("extensions/~¡中文😀/safe", false, "extensions/~¡中文😀/safe"),
                Arguments.of("extensions/CONSOLE/COM0/COM10/LPT0/LPT10/conifer.txt", false, "extensions/CONSOLE/COM0/COM10/LPT0/LPT10/conifer.txt"),
                Arguments.of(depth(32), false, depth(32)), Arguments.of(depth(32) + "/", true, depth(32)));
    }

    @ParameterizedTest(name = "{0}, directory={1}")
    @MethodSource("validPaths")
    void validatePathAcceptsAllowedRootsAndSafeSegments(String raw, boolean directory, String expected) {
        assertThat(QbankArchiveReader.validatePath(raw, directory, DEFAULT)).isEqualTo(expected);
    }

    static Stream<Arguments> invalidPaths() {
        List<Arguments> cases = new ArrayList<>();
        for (String name : List.of("", "/", "/question-banks/a", "C:/question-banks/a", "anything.txt", "assets/image.png",
                "Question-banks/a", "EXTENSIONS/a", "question-banks-other/a", "quizforge-package.json/child", "question-banks//a", "question-banks/a//b",
                "question-banks/./a", "question-banks/../a", "question-banks/a/../b", "extensions/ ", "extensions/\u2003",
                "extensions/file ", "extensions/file.", "extensions/intermediate /file", "extensions/intermediate./file", depth(33))) {
            cases.add(Arguments.of(printable(name), name, false));
        }
        cases.add(Arguments.of("format marker as directory", "quizforge-package.json/", true));
        cases.add(Arguments.of("empty directory", "/", true));
        cases.add(Arguments.of("double trailing slash", "extensions/a//", true));
        cases.add(Arguments.of("file trailing slash", "extensions/a/", false));
        cases.add(Arguments.of("directory depth 33", depth(33) + "/", true));
        "\\:*?\"<>|".chars().forEach(c -> cases.add(Arguments.of("forbidden U+%04X".formatted(c), "extensions/a" + (char) c + "b/file", false)));
        IntStream.concat(IntStream.rangeClosed(0, 31), IntStream.rangeClosed(127, 159)).forEach(c ->
                cases.add(Arguments.of("control U+%04X".formatted(c), "extensions/a" + (char) c + "b/file", false)));
        for (String device : List.of("CON", "PRN", "AUX", "NUL", "COM1", "COM9", "LPT1", "LPT9", "con.txt", "CoM1.dat", "lpt9.tar.gz")) {
            cases.add(Arguments.of("device leaf " + device, "extensions/" + device, false));
            cases.add(Arguments.of("device middle " + device, "question-banks/group/" + device + "/bank.json", false));
        }
        IntStream.rangeClosed(2, 8).forEach(number -> {
            for (String prefix : List.of("COM", "LPT")) cases.add(Arguments.of("device " + prefix + number,
                    "extensions/" + prefix + number + "/file", false));
        });
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPaths")
    void validatePathRejectsEveryUnsafeRule(String description, String raw, boolean directory) {
        rejected(() -> QbankArchiveReader.validatePath(raw, directory, DEFAULT), INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anything.txt", "question-banks/a/../bank.json", "extensions/CON/file", "quizforge-package.json/"})
    void readActuallyAppliesPathValidation(String name) throws Exception {
        rejected(() -> read(raw(entry(name, "bad"))), INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"question-banks/组/bank.json", "question-banks/组/BANK.JSON"})
    void rejectsExactAndCaseOnlyDuplicateFileNames(String secondName) throws Exception {
        rejected(() -> read(raw(entry("question-banks/组/bank.json", "first"), entry(secondName, "second"))), INVALID);
    }

    @Test
    void rejectsDirectoryFileAliasAndDuplicateDirectories() throws Exception {
        rejected(() -> read(raw(entry("extensions/a/", ""), entry("extensions/a", "file"))), INVALID);
        rejected(() -> read(raw(entry("extensions/a/", ""), entry("extensions/A/", ""))), INVALID);
    }

    @Test
    @ResourceLock("default-locale")
    void duplicateCaseFoldingDoesNotDependOnTurkishDefaultLocale() throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            rejected(() -> read(raw(entry("extensions/FILE", "first"), entry("extensions/file", "second"))), INVALID);
        } finally { Locale.setDefault(original); }
    }

    @Test
    void entryCountExactlyAtLimitSucceedsAndOneMoreIncludingDirectoryIsLimit() throws Exception {
        ArchiveLimits limits = new ArchiveLimits(2, 8, 16, 32);
        List<String> files = new ArrayList<>();
        QbankArchiveReader.read(raw(entry("extensions/", ""), entry("extensions/a", "12345678")), limits,
                (name, bytes) -> files.add(name));
        assertThat(files).containsExactly("extensions/a");
        rejected(() -> QbankArchiveReader.read(raw(entry("extensions/", ""), entry("extensions/a", "1"), entry("extensions/b/", "")), limits,
                (name, bytes) -> {}), LIMIT);
    }

    @Test
    void singleFileExactlyAtLimitSucceedsAndOneByteMoreIsLimit() throws Exception {
        ArchiveLimits limits = new ArchiveLimits(3, 8, 32, 32);
        List<byte[]> files = new ArrayList<>();
        QbankArchiveReader.read(raw(entry("extensions/a", "12345678")), limits, (name, bytes) -> files.add(bytes));
        assertThat(files).singleElement().satisfies(bytes -> assertThat(bytes).containsExactly(utf8("12345678")));
        rejected(() -> QbankArchiveReader.read(raw(entry("extensions/a", "123456789")), limits, (name, bytes) -> {}), LIMIT);
    }

    @Test
    void cumulativeBytesExactlyAtLimitSucceedAndMultipleSmallFilesCanExceedIt() throws Exception {
        ArchiveLimits limits = new ArchiveLimits(3, 8, 10, 32);
        assertThatCode(() -> QbankArchiveReader.read(raw(entry("extensions/a", "12345"), entry("extensions/b", "67890")), limits,
                (name, bytes) -> {})).doesNotThrowAnyException();
        List<String> delivered = new ArrayList<>();
        rejected(() -> QbankArchiveReader.read(raw(entry("extensions/a", "123456"), entry("extensions/b", "78901")), limits,
                (name, bytes) -> delivered.add(name)), LIMIT);
        assertThat(delivered).containsExactly("extensions/a");
    }

    @Test
    void cumulativeLimitCountsActualBytesEvenWhenDeclaredSizesAreForged() throws Exception {
        ArchiveLimits limits = new ArchiveLimits(3, 8, 10, 32);
        rejected(() -> QbankArchiveReader.read(raw(ZipBytes.deflatedZeros("extensions/a", 6).size(1),
                ZipBytes.deflatedZeros("extensions/b", 6).size(1)), limits, (name, bytes) -> {}), LIMIT);
    }

    @Test
    @Timeout(40)
    void forgedSmallSizeBombIsLimitWithHeapGrowthBoundedByEntryLimit() throws Exception {
        long entryLimit = 1024 * 1024;
        ZipBytes.Entry bomb = ZipBytes.deflatedZeros("question-banks/bomb.bin", 200L * 1024 * 1024).size(16);
        Path zip = raw(bomb);
        assertThat(Files.size(zip)).isLessThan(300 * 1024);
        Path output = temporary.resolve("bomb-probe.log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(executable, "-Xms16m", "-Xmx32m", "-XX:+UseSerialGC", "-cp", classpath,
                ArchiveBombProbe.class.getName(), zip.toString(), Long.toString(entryLimit))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(25, TimeUnit.SECONDS)).as("bounded child completion").isTrue();
            String log = Files.readString(output);
            assertThat(child.exitValue()).as(log).isZero();
            assertThat(log).contains("LIMIT; heapGrowth=", "budget=10485760").doesNotContain("OutOfMemoryError");
            System.out.println("Archive bomb probe: " + log.strip());
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }

    @Test
    void tamperedStoredDataAndForgedCrcAreInvalidBeforeHandler() throws Exception {
        ZipBytes.Entry original = entry("extensions/a", "abcdef");
        byte[] damaged = original.encoded().clone(); damaged[2] ^= 1;
        rejected(() -> read(raw(original.encoded(damaged))), INVALID);
        rejected(() -> read(raw(original.crc(original.crc() ^ 1))), INVALID);
    }

    static Stream<Arguments> brokenFiles() {
        return Stream.of(Arguments.of("empty file", new byte[0]), Arguments.of("plain text", utf8("not a ZIP")),
                Arguments.of("local header only", new byte[] {0x50, 0x4b, 3, 4, 0, 0}),
                Arguments.of("random binary", new byte[] {-1, 0, 13, 27, 8}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenFiles")
    void brokenNonZipAndEmptyFilesAreInvalid(String description, byte[] bytes) throws Exception {
        Path file = Files.write(temporary.resolve("broken.qbank"), bytes);
        rejected(() -> read(file), INVALID);
    }

    @Test
    void truncatedCentralDirectoryIsInvalid() throws Exception {
        Path zip = raw(entry("extensions/a", "content"));
        byte[] bytes = Files.readAllBytes(zip);
        Files.write(zip, Arrays.copyOf(bytes, bytes.length - 12));
        rejected(() -> read(zip), INVALID);
    }

    @Test
    void illegalUtf8NameIsInvalid() throws Exception {
        byte[] name = utf8("extensions/x"); name[name.length - 1] = (byte) 0xff;
        rejected(() -> read(raw(entry("unused", "bytes").named(name))), INVALID);
    }

    @Test
    void encryptedFlagIsInvalid() throws Exception {
        rejected(() -> read(raw(entry("extensions/a", "bytes").flags(0x801))), INVALID);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0100000})
    void ordinaryUnixFileModesAreAccepted(int mode) throws Exception {
        List<String> names = new ArrayList<>();
        QbankArchiveReader.read(raw(entry("extensions/rules.js", "code").mode(mode)), DEFAULT,
                (name, bytes) -> names.add(name));
        assertThat(names).containsExactly("extensions/rules.js");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0040000})
    void ordinaryUnixDirectoryModesAreAcceptedWithoutCallback(int mode) throws Exception {
        List<String> names = new ArrayList<>();
        QbankArchiveReader.read(raw(entry("extensions/group/", "").mode(mode), entry("extensions/group/file", "code")), DEFAULT,
                (name, bytes) -> names.add(name));
        assertThat(names).containsExactly("extensions/group/file");
    }

    @Test
    void legacyUnzipTransportsHiddenFilesWithoutApplyingCopyOrInstallPolicy() throws Exception {
        List<String> names = new ArrayList<>();
        QbankArchiveReader.read(raw(entry("extensions/group/type/.rules.js", "rules"), entry("extensions/group/type/.secret", "private")),
                DEFAULT, (name, bytes) -> names.add(name));
        assertThat(names).containsExactly("extensions/group/type/.rules.js", "extensions/group/type/.secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void legacyFormatVersionRejectionBelongsToInstallerNotUnzip(int version) throws Exception {
        String json = "{\"format\":\"quizforge-bank-group\",\"formatVersion\":" + version + "}";
        List<byte[]> contents = new ArrayList<>();
        QbankArchiveReader.read(raw(entry("quizforge-package.json", json)), DEFAULT, (name, bytes) -> contents.add(bytes));
        assertThat(contents).singleElement().satisfies(bytes -> assertThat(bytes).containsExactly(utf8(json)));
    }

    @Test
    void handlerRuntimeExceptionEscapesUnchangedAndZipIsClosed() throws Exception {
        Path zip = raw(entry("extensions/a", "a"), entry("extensions/b", "b"));
        RuntimeException failure = new RuntimeException("handler failure");
        List<String> called = new ArrayList<>();
        assertThat(catchThrowable(() -> QbankArchiveReader.read(zip, DEFAULT, (name, bytes) -> {
            called.add(name); throw failure;
        }))).isSameAs(failure);
        assertThat(called).containsExactly("extensions/a");
        Files.delete(zip);
        assertThat(zip).doesNotExist();
    }

    @Test
    void handlerArchiveRejectedExceptionAlsoEscapesUnchanged() throws Exception {
        ArchiveRejectedException failure = ArchiveRejectedException.limit("downstream limit");
        assertThat(catchThrowable(() -> QbankArchiveReader.read(raw(entry("extensions/a", "a")), DEFAULT,
                (name, bytes) -> { throw failure; }))).isSameAs(failure);
    }

    @Test
    void defaultsMatchLegacyUnzipLimits() {
        assertThat(DEFAULT).isEqualTo(new ArchiveLimits(10_000, 8L * 1024 * 1024, 128L * 1024 * 1024, 32));
    }

    @Test
    @Disabled("见报告第 1 条")
    void rejectsEmptyZip() throws Exception {
        rejected(() -> read(raw()), INVALID);
    }

    @ParameterizedTest
    @ValueSource(ints = {0120000, 0010000, 0060000, 0040000})
    @Disabled("见报告第 2 条")
    void rejectsUnixSpecialFilesAndDirectoryModeWithoutTrailingSlash(int mode) throws Exception {
        rejected(() -> read(raw(entry("extensions/link", "target").mode(mode))), INVALID);
    }

    @Test
    @Disabled("见报告第 2 条")
    void rejectsRegularFileModeWithDirectoryTrailingSlash() throws Exception {
        rejected(() -> read(raw(entry("extensions/not-a-directory/", "").mode(0100000))), INVALID);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 128})
    @Disabled("见报告第 3 条")
    void rejectsForgedUncompressedSizeEvenWhenActualDataFitsLimits(long declaredSize) throws Exception {
        rejected(() -> read(raw(ZipBytes.deflatedZeros("extensions/a", 64).size(declaredSize))), INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00a0", "\u2007", "\u202f", "\ufeff"})
    @Disabled("见报告第 4 条")
    void rejectsSegmentsBlankAccordingToLegacyJavascriptTrim(String blank) {
        rejected(() -> QbankArchiveReader.validatePath("extensions/" + blank + "/file", false, DEFAULT), INVALID);
    }

    private Path raw(ZipBytes.Entry... entries) throws IOException {
        return ZipBytes.write(temporary.resolve("sample-" + sequence++ + ".qbank"), entries);
    }

    private static ZipBytes.Entry entry(String name, String value) { return ZipBytes.Entry.stored(name, utf8(value)); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String depth(int depth) { return "extensions/" + "d/".repeat(depth - 2) + "file"; }
    private static String printable(String value) { return value.replace("\u2003", "U+2003"); }
    private static void read(Path zip) { QbankArchiveReader.read(zip, DEFAULT, (name, bytes) -> {}); }

    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ArchiveRejectedException.Reason reason) {
        Throwable failure = catchThrowable(action);
        assertThat(failure).isInstanceOf(ArchiveRejectedException.class);
        assertThat(((ArchiveRejectedException) failure).reason()).isEqualTo(reason);
    }
}
