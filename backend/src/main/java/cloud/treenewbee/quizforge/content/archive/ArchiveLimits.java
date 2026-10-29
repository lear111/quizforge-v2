package cloud.treenewbee.quizforge.content.archive;

/** .qbank 解压限制，数值与旧版 library-archive.js 一致（契约 C-01）。 */
public record ArchiveLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxDepth) {

    public static final ArchiveLimits DEFAULT =
            new ArchiveLimits(10_000, 32L * 1024 * 1024, 256L * 1024 * 1024, 32);
}
