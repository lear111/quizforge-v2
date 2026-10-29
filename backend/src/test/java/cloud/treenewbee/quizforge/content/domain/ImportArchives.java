package cloud.treenewbee.quizforge.content.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** ZIPs built from the same five real runner fixtures as the parser tests. */
public final class ImportArchives {
    public static final List<String> TYPES = List.of("单选题", "多选题", "判断题", "简答题", "简答题（答题区）");
    public static final String GROUP = PackageFixture.GROUP;
    private ImportArchives() {}

    public static Map<String, byte[]> files(List<String> types, String scope) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String type : types) {
            PackageFixture fixture = new PackageFixture(type);
            fixture.marker(marker -> marker.put("scope", scope));
            fixture.files.forEach((path, bytes) -> result.put(path.startsWith(PackageFixture.BANK + "/")
                    ? path.replace(PackageFixture.BANK + "/", bank(type) + "/") : path, bytes));
        }
        return result;
    }

    public static String bank(String name) { return "question-banks/" + GROUP + "/" + name; }
    public static String extension(String name) { return "extensions/" + GROUP + "/" + name; }
    public static void edit(Map<String, byte[]> files, String path, Consumer<Map<String, Object>> change) {
        var document = PackageFixture.document(files.get(path)); change.accept(document);
        files.put(path, PackageFixture.JSON.writeValueAsBytes(document));
    }
    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
