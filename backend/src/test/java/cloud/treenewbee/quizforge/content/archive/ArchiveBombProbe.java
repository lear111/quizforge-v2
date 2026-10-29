package cloud.treenewbee.quizforge.content.archive;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Path;

/** Runs in a small, isolated heap so a regression cannot exhaust Maven's test JVM. */
public final class ArchiveBombProbe {
    public static void main(String[] args) {
        long entryLimit = Long.parseLong(args[1]);
        ArchiveLimits limits = new ArchiveLimits(2, entryLimit, entryLimit * 2, 32);
        // Load classes before resetting the memory baseline.
        QbankArchiveReader.validatePath("question-banks/bomb.bin", false, limits);
        ArchiveRejectedException.limit("warmup");
        System.gc();
        long baseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        var pools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
        pools.forEach(pool -> pool.resetPeakUsage());
        try {
            QbankArchiveReader.read(Path.of(args[0]), limits, (name, bytes) -> {
                throw new AssertionError("Oversized file reached handler");
            });
            throw new AssertionError("Bomb was accepted");
        } catch (ArchiveRejectedException rejected) {
            if (rejected.reason() != ArchiveRejectedException.Reason.LIMIT) throw new AssertionError(rejected);
        }
        long peak = pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        long growth = Math.max(0, peak - baseline);
        long budget = 8 * entryLimit + 2 * 1024 * 1024;
        if (growth > budget) throw new AssertionError("Heap growth " + growth + " exceeds budget " + budget);
        System.out.println("LIMIT; heapGrowth=" + growth + "; budget=" + budget);
    }
}
