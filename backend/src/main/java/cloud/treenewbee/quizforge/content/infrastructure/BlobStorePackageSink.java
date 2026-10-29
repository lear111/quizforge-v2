package cloud.treenewbee.quizforge.content.infrastructure;

import cloud.treenewbee.quizforge.content.domain.QbankPackageParser;
import java.io.IOException;

/** Explicit adapter; constructing a parser does not start Spring or open a business transaction. */
public final class BlobStorePackageSink implements QbankPackageParser.AssetSink {
    private final BlobStore blobs;
    public BlobStorePackageSink(BlobStore blobs) { this.blobs = java.util.Objects.requireNonNull(blobs); }
    @Override public String store(byte[] bytes) throws IOException { return blobs.put(bytes); }
}
