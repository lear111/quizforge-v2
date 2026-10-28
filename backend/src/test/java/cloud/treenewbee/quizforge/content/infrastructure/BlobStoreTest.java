package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.shared.id.Ids;

class BlobStoreTest extends MySqlTestBase {
    @TempDir Path directory;
    @Autowired BlobMapper blobs;
    @Autowired JdbcTemplate jdbc;
    private final List<String> registered = new ArrayList<>();

    private BlobStore store(BlobMapper mapper) throws IOException {
        return new BlobStore(directory.toString(), mapper, Clock.fixed(ContentMapperTest.NOW, ZoneOffset.UTC));
    }
    private byte[] content() { return ("内容😀-" + Ids.uuidV7()).getBytes(StandardCharsets.UTF_8); }
    private String hash(byte[] bytes) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        registered.add(hash); return hash;
    }
    private Path path(String hash) { return directory.resolve("sha256").resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash); }
    private void assertNoTemporaryFiles() throws IOException {
        Path temporary = directory.resolve(".tmp");
        if (Files.exists(temporary)) try (var files = Files.list(temporary)) { assertThat(files.count()).isZero(); }
    }

    @AfterEach
    void cleanOwnRows() {
        for (String hash : registered) jdbc.update("DELETE FROM content_blob WHERE sha256=?", HexFormat.of().parseHex(hash));
    }

    @Test
    void putOpenAndExistsUseRunnerCompatibleLayoutAndRegisterMetadata() throws Exception {
        byte[] bytes = content(); String hash = hash(bytes);
        BlobStore store = store(blobs);
        assertThat(store.exists(hash)).isFalse(); assertThat(store.put(bytes)).isEqualTo(hash);
        assertThat(store.exists(hash)).isTrue(); assertThat(Files.readAllBytes(path(hash))).containsExactly(bytes);
        try (var input = store.open(hash)) { assertThat(input.readAllBytes()).containsExactly(bytes); }
        BlobRow row = blobs.findBySha256(HexFormat.of().parseHex(hash)).orElseThrow();
        assertThat(row.size()).isEqualTo(bytes.length); assertThat(row.mime()).isEqualTo("application/octet-stream");
        assertThat(row.createdAt()).isEqualTo(ContentMapperTest.NOW);
        assertNoTemporaryFiles();
    }

    @Test
    void concurrentIdenticalWritesAcrossStoreInstancesPublishOneFileAndRowWithoutReplacingIt() throws Exception {
        byte[] bytes = content(); String hash = hash(bytes);
        BlobStore first = store(blobs), second = store(blobs);
        CyclicBarrier start = new CyclicBarrier(12);
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                BlobStore writer = index % 2 == 0 ? first : second;
                tasks.add(() -> { start.await(); return writer.put(bytes); });
            }
            for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isEqualTo(hash);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_blob WHERE sha256=?", Integer.class, HexFormat.of().parseHex(hash))).isEqualTo(1);
        FileTime oldTime = FileTime.fromMillis(1000000000);
        Files.setLastModifiedTime(path(hash), oldTime);
        assertThat(first.put(bytes)).isEqualTo(hash);
        assertThat(Files.getLastModifiedTime(path(hash))).isEqualTo(oldTime);
        assertThat(Files.readAllBytes(path(hash))).containsExactly(bytes);
        try (var files = Files.walk(directory.resolve("sha256"))) { assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1); }
        assertNoTemporaryFiles();
    }

    @Test
    void databaseFailureLeavesRecoverableImmutableFileAndRetryRegistersIt() throws Exception {
        byte[] bytes = content(); String hash = hash(bytes);
        BlobMapper failing = mock(BlobMapper.class);
        doThrow(new DataAccessResourceFailureException("injected DB fault")).when(failing).insertIfAbsent(any());
        assertThatThrownBy(() -> store(failing).put(bytes)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(Files.readAllBytes(path(hash))).containsExactly(bytes);
        assertThat(blobs.findBySha256(HexFormat.of().parseHex(hash))).isEmpty();
        assertThat(store(blobs).put(bytes)).isEqualTo(hash);
        assertThat(blobs.findBySha256(HexFormat.of().parseHex(hash))).isPresent();
    }

    @Test
    void existingCorruptFileIsNeverOverwrittenOrRegistered() throws Exception {
        byte[] bytes = content(); String hash = hash(bytes);
        Files.createDirectories(path(hash).getParent()); Files.writeString(path(hash), "different bytes");
        assertThatThrownBy(() -> store(blobs).put(bytes)).isInstanceOf(IOException.class);
        assertThat(Files.readString(path(hash))).isEqualTo("different bytes");
        assertThat(blobs.findBySha256(HexFormat.of().parseHex(hash))).isEmpty();
        assertNoTemporaryFiles();
    }

    @Test
    void invalidHashesAndMissingFileCannotEscapeTheBlobRoot() throws Exception {
        BlobStore store = store(blobs);
        for (String hash : new String[] {"../secret", "A".repeat(64), "a".repeat(63), "a".repeat(65), ""}) {
            assertThatThrownBy(() -> store.open(hash)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.exists(hash)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.open("a".repeat(64))).isInstanceOf(IOException.class);
    }

    @Test
    void nonDirectoryShardIsRejectedWithoutLeavingTemporaryFiles() throws Exception {
        byte[] bytes = content(); hash(bytes);
        Files.writeString(directory.resolve("sha256"), "not a directory");
        assertThatThrownBy(() -> store(blobs).put(bytes)).isInstanceOf(IOException.class);
        assertThat(Files.readString(directory.resolve("sha256"))).isEqualTo("not a directory");
        assertNoTemporaryFiles();
    }

    @Test
    void emptyContentHasStandardHashAndRemainsReadable() throws Exception {
        String hash = hash(new byte[0]);
        BlobStore store = store(blobs);
        assertThat(store.put(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        try (var stream = store.open(hash)) { assertThat(stream.read()).isEqualTo(-1); }
    }
}
