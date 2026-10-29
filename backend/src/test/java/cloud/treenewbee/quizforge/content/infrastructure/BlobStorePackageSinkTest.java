package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class BlobStorePackageSinkTest {
    @Test
    void delegatesOriginalBytesAndHashWithoutStartingSpring() throws Exception {
        BlobStore blobs = mock(BlobStore.class); byte[] bytes = {1, 2, 3};
        when(blobs.put(bytes)).thenReturn("a".repeat(64));
        assertThat(new BlobStorePackageSink(blobs).store(bytes)).isEqualTo("a".repeat(64));
        verify(blobs).put(same(bytes)); verifyNoMoreInteractions(blobs);
    }
    @Test
    void propagatesStorageIOExceptionUnchanged() throws Exception {
        BlobStore blobs = mock(BlobStore.class); byte[] bytes = {1}; IOException failure = new IOException("disk");
        when(blobs.put(bytes)).thenThrow(failure);
        assertThat(catchThrowable(() -> new BlobStorePackageSink(blobs).store(bytes))).isSameAs(failure);
    }
}
