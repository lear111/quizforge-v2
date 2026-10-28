package cloud.treenewbee.quizforge.content.infrastructure;

public record ExtensionFileRow(
        byte[] packageDigest,
        String path,
        byte[] blobSha256) {}
