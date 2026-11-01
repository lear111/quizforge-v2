package cloud.treenewbee.quizforge.practice.resources;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import cloud.treenewbee.quizforge.shared.web.ApiException;

class ImageUploadsTest {
    @Test void fileHeadersDetermineMimeAndExecutableBytesAreRejected() {
        byte[] png = new byte[24];
        System.arraycopy(new byte[]{(byte)137,80,78,71,13,10,26,10},0,png,0,8);
        System.arraycopy("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,png,12,4);
        assertThat(ImageUploads.detect(png)).isEqualTo("image/png");
        assertThat(ImageUploads.detect(new byte[]{(byte)255,(byte)216,(byte)255,0,(byte)255,(byte)217})).isEqualTo("image/jpeg");
        assertThat(ImageUploads.detect("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        for (byte[] bytes : new byte[][]{"<script>alert(1)</script>".getBytes(),"GIF89a0000".getBytes(),new byte[0]})
            assertThatThrownBy(() -> ImageUploads.detect(bytes)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("IMAGE_TYPE_UNSUPPORTED"));
    }
    @Test void decodeEnforcesActualByteLimitAndRejectsMalformedBase64() {
        byte[] bytes = new byte[ImageUploads.MAX_BYTES];
        assertThat(ImageUploads.decode(Base64.getEncoder().encodeToString(bytes))).hasSize(ImageUploads.MAX_BYTES);
        assertThatThrownBy(() -> ImageUploads.decode(Base64.getEncoder().encodeToString(new byte[ImageUploads.MAX_BYTES+1])))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("IMAGE_SIZE_LIMIT"));
        for (String value : new String[]{"", "a===", "data:image/png;base64,AAAA", "###"})
            assertThatThrownBy(() -> ImageUploads.decode(value)).isInstanceOf(ApiException.class);
    }
    @Test void uploadRateIsPerUserAndResetsWithInjectedClock() {
        var now = new AtomicReference<>(Instant.parse("2026-11-01T00:00:00Z"));
        var clock = new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var limiter = new UploadLimiter(clock);
        for (int i=0;i<30;i++) limiter.acquire("a");
        limiter.acquire("b");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("RESOURCE_RATE_LIMIT"));
        now.set(now.get().plusSeconds(60));
        limiter.acquire("a");
    }
    @Test void concurrentUploadsCannotExceedThirtyPerUser() throws Exception {
        var limiter = new UploadLimiter(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        var start = new CountDownLatch(1); var accepted = new AtomicInteger();
        try (var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0,60).mapToObj(i -> workers.submit(() -> {
                start.await(); try{limiter.acquire("same");accepted.incrementAndGet();}catch(ApiException e){assertThat(e.code()).isEqualTo("RESOURCE_RATE_LIMIT");}return null;
            })).toList();
            start.countDown(); for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(30);
    }
}
