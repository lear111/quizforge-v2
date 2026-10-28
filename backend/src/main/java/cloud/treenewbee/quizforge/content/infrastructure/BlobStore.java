package cloud.treenewbee.quizforge.content.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable content-addressed files. Files publish before their idempotent database registration. */
@Component
public final class BlobStore {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];
    static { for (int index = 0; index < LOCKS.length; index++) LOCKS[index] = new ReentrantLock(); }

    private final Path root;
    private final BlobMapper blobs;
    private final Clock clock;

    public BlobStore(@Value("${qf.storage.blob-dir}") String directory, BlobMapper blobs, Clock clock) throws IOException {
        Path configured = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.root = configured.toRealPath();
        this.blobs = blobs;
        this.clock = clock;
    }

    public String put(byte[] bytes) throws IOException {
        // The caller may reuse its array while this write is pending.
        byte[] content = bytes.clone();
        String sha256 = HexFormat.of().formatHex(digest(content));
        Path target = path(sha256);
        if (exists(sha256)) {
            verifyExisting(target, sha256);
            register(sha256, content.length);
            return sha256;
        }
        Path temporaryDirectory = directory(root.resolve(".tmp"));
        Path temporary = Files.createTempFile(temporaryDirectory, "blob-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) output.write(buffer);
                // The read-only runner uses a different UID on the shared volume.
                try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--")); }
                catch (UnsupportedOperationException ignored) { /* Windows ACLs govern visibility. */ }
                output.force(true);
            }
            ReentrantLock local = LOCKS[Math.floorMod(root.hashCode(), LOCKS.length)];
            local.lock();
            try (FileChannel lock = FileChannel.open(root.resolve(".publish.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = lock.lock()) {
                // OS lock coordinates separate JVMs; JVM lock avoids overlapping FileLocks.
                directory(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    verifyExisting(target, sha256);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    forceDirectory(target.getParent());
                }
            } finally { local.unlock(); }
            // A failed DB write may leave an orphan. A retry registers the same immutable file.
            register(sha256, content.length);
            return sha256;
        } finally { Files.deleteIfExists(temporary); }
    }

    private void register(String sha256, int size) {
        blobs.insertIfAbsent(new BlobRow(HexFormat.of().parseHex(sha256), size,
                "application/octet-stream", clock.instant().truncatedTo(ChronoUnit.MILLIS)));
    }

    private void verifyExisting(Path target, String sha256) throws IOException {
        safeFile(target);
        try (InputStream existing = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!MessageDigest.isEqual(digest(existing), HexFormat.of().parseHex(sha256))) {
                throw new IOException("Existing blob content does not match its address");
            }
        }
    }

    public InputStream open(String sha256) throws IOException {
        Path file = path(sha256);
        safeFile(file);
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
    }

    public boolean exists(String sha256) throws IOException {
        Path file = path(sha256);
        checkParents(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        safeFile(file);
        return true;
    }

    private Path path(String sha256) {
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        return root.resolve("sha256").resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private Path directory(Path directory) throws IOException {
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                    try { Files.setPosixFilePermissions(current, PosixFilePermissions.fromString("rwxr-xr-x")); }
                    catch (UnsupportedOperationException ignored) { /* Windows ACLs govern visibility. */ }
                    forceDirectory(current.getParent());
                }
                catch (java.nio.file.FileAlreadyExistsException ignored) { /* Another writer created it. */ }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe blob directory");
            }
        }
        return directory;
    }

    private void checkParents(Path file) throws IOException {
        Path current = root;
        for (Path part : root.relativize(file.getParent())) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Unsafe blob directory");
            }
        }
    }

    private void safeFile(Path file) throws IOException {
        checkParents(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Blob is missing or unsafe");
    }

    private static byte[] digest(byte[] value) {
        return sha256().digest(value);
    }

    private static byte[] digest(InputStream input) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Java's Windows provider cannot open directory channels. Linux deployments can fsync them.
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
