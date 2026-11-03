package cloud.treenewbee.quizforge.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.admin.application.ExtensionReviewService;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.content.infrastructure.ImportCatalogMapper;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

class ImportUploadServiceTest {
    @TempDir Path temporary;

    @Test
    void chunkedUploadCannotBypassConfiguredLimitAndTemporaryFileIsRemoved() throws Exception {
        CatalogMapper catalog = mock(CatalogMapper.class);
        BlobStore blobs = mock(BlobStore.class);
        QuotaService quotas = mock(QuotaService.class);
        when(quotas.usage("user")).thenReturn(new QuotaService.Usage(new QuotaService.Amount(0, 1000, null), null, null, null));
        var service = new ImportUploadService(catalog, mock(ImportCatalogMapper.class), mock(ImportQueueMapper.class),
                blobs, quotas, mock(TransactionTemplate.class), Clock.systemUTC(), JsonMapper.builder().build(),
                32, 20, temporary.toString(), mock(ApplicationEventPublisher.class), mock(ExtensionReviewService.class));
        assertThatThrownBy(() -> service.receivePrivate("user", null, "large.qbank", -1, new ByteArrayInputStream(new byte[33])))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException)error).code()).isEqualTo("LIBRARY_ARCHIVE_LIMIT");
        verifyNoInteractions(blobs, catalog);
        try (var paths = Files.list(temporary)) { assertThat(paths).isEmpty(); }
    }
}
