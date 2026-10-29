package cloud.treenewbee.quizforge.content.archive;

/** .qbank 通用解压限制（题库包格式 4，详细设计 06 第 7 节）；按资源类型的上限由包结构解析检查。 */
public record ArchiveLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxDepth) {

    public static final ArchiveLimits DEFAULT =
            new ArchiveLimits(10_000, 32L * 1024 * 1024, 256L * 1024 * 1024, 32);
}
