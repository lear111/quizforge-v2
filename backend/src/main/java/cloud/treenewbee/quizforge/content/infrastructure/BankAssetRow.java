package cloud.treenewbee.quizforge.content.infrastructure;

public record BankAssetRow(
        String bankId,
        String path,
        byte[] blobSha256) {}
