package cloud.treenewbee.quizforge.ai.grading;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GradingPromptTest {
    final JsonMapper json = JsonMapper.builder().build();
    final ContentPages pages = mock(ContentPages.class);
    final BlobStore blobs = mock(BlobStore.class);
    final GradingPrompt prompt = new GradingPrompt(pages,blobs);
    final String sha = "a".repeat(64);

    @Test void authorizedImageBecomesDataUrlAndProviderNeverFetchesAResourceUrl() throws Exception {
        when(pages.resource("u",sha)).thenReturn(new ContentPages.FileResponse("/internal", "image/png"));
        when(blobs.open(sha)).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        var request = prompt.request("u",input(1),false);
        var contents = ((UserMessage)request.messages().get(1)).contents();
        var image = (ImageContent)contents.stream().filter(ImageContent.class::isInstance).findFirst().orElseThrow();
        assertThat(image.image().url().toString()).isEqualTo("data:image/png;base64,AQID");
        verify(pages).resource("u",sha);
    }

    @Test void unauthorizedImageIsNeverRead() throws Exception {
        when(pages.resource("u",sha)).thenThrow(ContentPages.notFound());
        assertThatThrownBy(() -> prompt.request("u",input(1),false)).isInstanceOf(ApiException.class);
        verifyNoInteractions(blobs);
    }

    @Test void individualImageHasFourMiBLimit() throws Exception {
        when(pages.resource("u",sha)).thenReturn(new ContentPages.FileResponse("/internal", "image/png"));
        when(blobs.open(sha)).thenAnswer(call -> new ByteArrayInputStream(new byte[4*1024*1024+1]));
        assertThatThrownBy(() -> prompt.request("u",input(1),false)).isInstanceOf(ApiException.class).hasMessageContaining("4 MiB");
    }

    @Test void combinedImagesHaveTwelveMiBLimit() throws Exception {
        when(pages.resource("u",sha)).thenReturn(new ContentPages.FileResponse("/internal", "image/png"));
        when(blobs.open(sha)).thenAnswer(call -> new ByteArrayInputStream(new byte[4*1024*1024]));
        assertThatThrownBy(() -> prompt.request("u",input(4),false)).isInstanceOf(ApiException.class).hasMessageContaining("12 MiB");
    }

    private tools.jackson.databind.JsonNode input(int images) {
        var picture = Map.of("type","image","assetId",sha);
        return json.valueToTree(Map.of("question",java.util.Collections.nCopies(images,picture),"answer",List.of(),"referenceAnswer",List.of(),"maxScore",5,"scoreStep",0.5));
    }
}
