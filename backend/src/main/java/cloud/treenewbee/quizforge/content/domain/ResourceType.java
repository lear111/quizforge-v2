package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.LIBRARY_ARCHIVE_LIMIT;
import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;

/** The three rows of design 06 §6; signatures identify containers, not complete media validity. */
public enum ResourceType {
    IMAGE(4L * 1024 * 1024, true,
            signature(bytes -> starts(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10), "png"),
            signature(bytes -> starts(bytes, 0, 0xff, 0xd8, 0xff), "jpg", "jpeg"),
            signature(bytes -> ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP"), "webp"),
            signature(bytes -> ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a"), "gif")),
    AUDIO(32L * 1024 * 1024, true,
            signature(bytes -> ascii(bytes, 0, "ID3") || bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 0xe0) == 0xe0, "mp3"),
            signature(bytes -> ascii(bytes, 4, "ftyp"), "m4a")),
    VIDEO(null, false,
            signature(bytes -> ascii(bytes, 4, "ftyp"), "mp4"),
            signature(bytes -> starts(bytes, 0, 0x1a, 0x45, 0xdf, 0xa3), "webm"));

    private final Long maxBytes;
    private final boolean enabled;
    private final Signature[] signatures;
    private final Set<String> extensions;

    ResourceType(Long maxBytes, boolean enabled, Signature... signatures) {
        this.maxBytes = maxBytes;
        this.enabled = enabled;
        this.signatures = signatures;
        Set<String> names = new LinkedHashSet<>();
        for (Signature signature : signatures) names.addAll(signature.extensions());
        this.extensions = Collections.unmodifiableSet(names);
    }

    public Set<String> extensions() { return extensions; }
    public OptionalLong maxBytes() { return maxBytes == null ? OptionalLong.empty() : OptionalLong.of(maxBytes); }
    public boolean enabled() { return enabled; }

    public static ResourceType detect(String fileName, byte[] bytes) {
        if (fileName == null || bytes == null) throw unsupported(String.valueOf(fileName));
        int dot = fileName.lastIndexOf('.');
        String suffix = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (ResourceType type : values()) {
            for (Signature signature : type.signatures) {
                if (signature.extensions().contains(suffix) && signature.header().test(bytes)) {
                    if (!type.enabled) throw unsupported(fileName);
                    if (bytes.length > type.maxBytes) throw new PackageRejectedException(LIBRARY_ARCHIVE_LIMIT, "资源文件超过大小上限：" + fileName);
                    return type;
                }
            }
        }
        throw unsupported(fileName);
    }

    private static PackageRejectedException unsupported(String name) {
        return new PackageRejectedException(UNSUPPORTED_RESOURCE_TYPE, "不支持的资源类型：" + name);
    }

    private record Signature(Set<String> extensions, Predicate<byte[]> header) {}
    private static Signature signature(Predicate<byte[]> header, String... extensions) {
        return new Signature(Set.copyOf(Arrays.asList(extensions)), header);
    }
    private static boolean ascii(byte[] bytes, int offset, String text) {
        byte[] signature = text.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + signature.length) return false;
        for (int index = 0; index < signature.length; index++) if (bytes[offset + index] != signature[index]) return false;
        return true;
    }
    private static boolean starts(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) return false;
        for (int index = 0; index < signature.length; index++) if ((bytes[offset + index] & 255) != signature[index]) return false;
        return true;
    }
}
