package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceTypeTest {
    static byte[] png() { return new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}; }
    static byte[] ftyp() { return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0}; }

    static Stream<Arguments> signatures() {
        return Stream.of(
                Arguments.of("picture.png", png(), ResourceType.IMAGE),
                Arguments.of("自由命名.PNG", png(), ResourceType.IMAGE),
                Arguments.of("picture.jpg", new byte[] {-1, -40, -1}, ResourceType.IMAGE),
                Arguments.of("picture.JPEG", new byte[] {-1, -40, -1}, ResourceType.IMAGE),
                Arguments.of("picture.webp", new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, ResourceType.IMAGE),
                Arguments.of("picture.gif", "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII), ResourceType.IMAGE),
                Arguments.of("picture.GIF", "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII), ResourceType.IMAGE),
                Arguments.of("听力.mp3", new byte[] {'I', 'D', '3'}, ResourceType.AUDIO),
                Arguments.of("听力.MP3", new byte[] {-1, (byte) 0xe0}, ResourceType.AUDIO),
                Arguments.of("听力.m4a", ftyp(), ResourceType.AUDIO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signatures")
    void enabledFormatsMatchTheirOwnExtensionAndSignature(String name, byte[] bytes, ResourceType type) {
        assertThat(ResourceType.detect(name, bytes)).isEqualTo(type);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signatures")
    void truncatedOrDifferentSignatureIsUnsupported(String name, byte[] bytes, ResourceType type) {
        rejected(name, new byte[0], PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
        byte[] wrong = bytes.clone(); wrong[0] ^= 0x40; wrong[Math.min(4, wrong.length - 1)] ^= 0x40;
        rejected(name, wrong, PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
    }

    @Test
    void formatsWithinTheSameCategoryCannotMasqueradeAsEachOther() {
        rejected("picture.jpg", png(), PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
        rejected("picture.png", new byte[] {-1, -40, -1}, PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
        rejected("audio.mp3", ftyp(), PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
        rejected("audio.m4a", new byte[] {'I', 'D', '3'}, PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "file.bin", "file.exe", "file.svg", "file.wav", "file.png.exe", "file."})
    void unknownExtensionsAreRejectedEvenWithKnownImageBytes(String name) {
        rejected(name, png(), PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
    }

    @Test
    void videoRowsAreReservedDisabledAndHaveNoInventedSizeLimit() {
        assertThat(ResourceType.VIDEO.enabled()).isFalse();
        assertThat(ResourceType.VIDEO.maxBytes()).isEmpty();
        assertThat(ResourceType.VIDEO.extensions()).containsExactlyInAnyOrder("mp4", "webm");
        rejected("video.mp4", ftyp(), PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
        rejected("video.webm", new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, PackageRejectedException.Code.UNSUPPORTED_RESOURCE_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"picture.png", "audio.m4a"})
    void typeLimitAllowsExactBoundaryAndRejectsOneByteMore(String name) {
        ResourceType type = name.endsWith("png") ? ResourceType.IMAGE : ResourceType.AUDIO;
        byte[] header = type == ResourceType.IMAGE ? png() : ftyp();
        int limit = (int) type.maxBytes().orElseThrow();
        byte[] bytes = Arrays.copyOf(header, limit + 1);
        rejected(name, bytes, PackageRejectedException.Code.LIBRARY_ARCHIVE_LIMIT);
        assertThat(ResourceType.detect(name, Arrays.copyOf(bytes, limit))).isEqualTo(type);
    }

    private static void rejected(String name, byte[] bytes, PackageRejectedException.Code code) {
        Throwable error = catchThrowable(() -> ResourceType.detect(name, bytes));
        assertThat(error).isInstanceOf(PackageRejectedException.class);
        assertThat(((PackageRejectedException) error).code()).isEqualTo(code);
        assertThat(error.getMessage()).contains(name);
    }
}
