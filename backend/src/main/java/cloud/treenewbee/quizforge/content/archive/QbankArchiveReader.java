package cloud.treenewbee.quizforge.content.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class QbankArchiveReader {

    static final String MANIFEST = "quizforge-package.json";
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[\\\\:*?\"<>|\\x00-\\x1f\\x7f-\\x9f]");
    private static final Pattern RESERVED_NAME = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");
    // ECMAScript 的 WhiteSpace + LineTerminator（旧版 trim() 使用的集合）
    private static final Pattern JS_BLANK = Pattern.compile(
            "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]*");

    private QbankArchiveReader() {
    }

    /** 校验条目路径，返回去掉末尾 "/" 的规范路径；不合法时抛 INVALID。 */
    static String validatePath(String rawName, boolean directory, ArchiveLimits limits) {
        String name = directory ? rawName.substring(0, rawName.length() - 1) : rawName;
        String[] parts = name.split("/", -1);

        boolean topLevelAllowed = 
                ((name.equals(MANIFEST) && !directory) 
                || parts[0].equals("question-banks") || parts[0].equals("extensions"));

        if (parts.length > limits.maxDepth() || !topLevelAllowed) {
            throw ArchiveRejectedException.invalid("题库包顶层只允许格式标识、question-banks 和 extensions");
        }
        for (String part : parts) {
            if (JS_BLANK.matcher(part).matches() || part.equals(".") || part.equals("..") || 
                part.endsWith(" ") || part.endsWith(".") || 
                FORBIDDEN_CHARS.matcher(part).find() || RESERVED_NAME.matcher(part).matches()) {
                throw ArchiveRejectedException.invalid("压缩包包含无效或越界路径");
            }
        }
        return name;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, ArchiveLimits limits, long totalSoFar) throws IOException {
        if (entry.getSize() > limits.maxEntryBytes()) {
            throw ArchiveRejectedException.limit("压缩包单个文件超过大小上限");   // 提前拒绝，省得白读；但不能只靠它
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        long read = 0;

        try (InputStream in = zip.getInputStream(entry)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                read += n;
                if (read > limits.maxEntryBytes() || totalSoFar + read > limits.maxTotalBytes()) {
                    throw ArchiveRejectedException.limit("压缩包超过解压大小上限");
                }

                crc.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }

        if (entry.getCrc() == -1 || crc.getValue() != entry.getCrc()) { 
            throw ArchiveRejectedException.invalid("压缩包文件校验失败");
        }

        if (entry.getSize() != -1 && read != entry.getSize()) {
            throw ArchiveRejectedException.invalid("压缩包文件大小与记录不一致");
        }
        return out.toByteArray();
    }

    /** 每个通过检查的文件，按路径与内容交给调用方；目录不回调。 */
    @FunctionalInterface
    public interface EntryHandler {
        void accept(String path, byte[] content);
    }

    public static void read(Path zipFile, ArchiveLimits limits, EntryHandler handler) {
        try (ZipFile zip = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
            Set<String> seen = new HashSet<>();
            long total = 0;
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > limits.maxEntries()) {
                    throw ArchiveRejectedException.limit("压缩包文件数量超过上限");
                }

                String name = validatePath(entry.getName(), entry.isDirectory(), limits);

                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw ArchiveRejectedException.invalid("压缩包包含重复路径");
                }

                if (entry.isDirectory()) {
                    continue;
                }

                byte[] content = readEntry(zip, entry, limits, total);
                total += content.length;
                handler.accept(name, content);
            }
        } catch (IOException e) {
            throw ArchiveRejectedException.invalid("压缩包损坏或不是有效的 ZIP 文件");
        }
    }
}