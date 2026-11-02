package cloud.treenewbee.quizforge.library;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LibraryExportCleanupTest {
    @TempDir Path root;

    @Test void cleanupRemovesOnlyExpiredOwnedFilesAndLeavesCurrentDownloadsAlone() throws Exception {
        Instant now = Instant.parse("2026-11-02T12:00:00Z");
        Path directory = Files.createDirectories(root.resolve("exports"));
        Path expired = Files.writeString(directory.resolve(UUID.randomUUID() + ".qbank"), "synthetic");
        Path current = Files.writeString(directory.resolve(UUID.randomUUID() + ".qbank"), "synthetic");
        Path unrelated = Files.writeString(directory.resolve("do-not-delete.txt"), "synthetic");
        Path building = Files.writeString(directory.resolve("building-expired.tmp"), "synthetic");
        for (Path file : java.util.List.of(expired, building, unrelated)) Files.setLastModifiedTime(file, FileTime.from(now.minusSeconds(3601)));
        Files.setLastModifiedTime(current, FileTime.from(now.minusSeconds(3599)));
        new LibraryExportService(mock(LibraryMapper.class), mock(LibraryExportMapper.class), mock(BlobStore.class),
                JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC), root.toString(), 100).cleanup();
        assertThat(expired).doesNotExist();
        assertThat(building).doesNotExist();
        assertThat(current).exists();
        assertThat(unrelated).exists();
    }
}
