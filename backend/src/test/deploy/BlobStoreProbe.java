import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Deployment probe loaded with Boot's PropertiesLauncher; never included in the application jar. */
public final class BlobStoreProbe {
    public static void main(String[] args) throws Exception {
        Class<?> boot = Class.forName("org.springframework.boot.SpringApplication");
        Class<?> application = Class.forName("cloud.treenewbee.quizforge.QuizForgeApplication");
        Object context = boot.getMethod("run", Class.class, String[].class).invoke(null, application,
                new String[] {"--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR"});
        try {
            Class<?> storage = Class.forName("cloud.treenewbee.quizforge.content.infrastructure.BlobStore");
            Object store = context.getClass().getMethod("getBean", Class.class).invoke(context, storage);
            byte[] bytes = ("compose-blob-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
            String hash = (String) storage.getMethod("put", byte[].class).invoke(store, (Object) bytes);
            if (!Boolean.TRUE.equals(storage.getMethod("exists", String.class).invoke(store, hash))) throw new AssertionError("Missing blob");
            try (InputStream input = (InputStream) storage.getMethod("open", String.class).invoke(store, hash)) {
                if (!Arrays.equals(input.readAllBytes(), bytes)) throw new AssertionError("Blob bytes changed");
            }
            System.out.println("BLOB_SMOKE=" + hash);
        } finally { ((AutoCloseable) context).close(); }
    }
}
