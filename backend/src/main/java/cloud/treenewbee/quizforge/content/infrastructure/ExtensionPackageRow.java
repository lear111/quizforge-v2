package cloud.treenewbee.quizforge.content.infrastructure;

import java.time.Instant;

public record ExtensionPackageRow(
        byte[] digest,
        String extensionId,
        String version,
        String name,
        String manifest,
        int apiMajor,
        int apiMinMinor,
        Instant createdAt) {}
