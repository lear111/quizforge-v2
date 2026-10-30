package cloud.treenewbee.quizforge.content.application;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import cloud.treenewbee.quizforge.content.infrastructure.*;
import cloud.treenewbee.quizforge.rules.RulePackage;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ContentPages {
    private static final Map<String, String> MEDIA = Map.ofEntries(Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"), Map.entry("svg", "image/svg+xml"), Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"));
    private final ContentAccessMapper access;
    private final ExtensionPackageMapper packages;
    private final ExtensionFileMapper files;
    private final BlobStore blobs;
    private final SdkRegistry sdk;
    private final JsonMapper json;

    public ContentPages(ContentAccessMapper access, ExtensionPackageMapper packages, ExtensionFileMapper files,
            BlobStore blobs, SdkRegistry sdk, JsonMapper json) {
        this.access = access; this.packages = packages; this.files = files; this.blobs = blobs; this.sdk = sdk; this.json = json;
    }

    public ExtensionPackageRow extension(byte[] digest) { return packages.findByDigest(digest).orElseThrow(ContentPages::notFound); }

    public RulePackage rulePackage(ExtensionPackageRow extension) {
        JsonNode manifest = json.readTree(extension.manifest());
        return new RulePackage(hex(extension.digest()), new RulePackage.ApiVersion(extension.apiMajor(), extension.apiMinMinor()),
                SdkRegistry.declares(manifest, "outline-items"), SdkRegistry.declares(manifest, "answer-state"),
                ref(extension.digest(), manifest, "rules"), ref(extension.digest(), manifest, "questionSchema"),
                ref(extension.digest(), manifest, "answerSchema"));
    }

    private RulePackage.FileRef ref(byte[] digest, JsonNode manifest, String field) {
        return new RulePackage.FileRef(hex(file(digest, manifest.path(field).asString()).blobSha256()));
    }

    private ExtensionFileRow file(byte[] digest, String relative) {
        // Resolve only an exact manifest-declared DB key, never a caller-supplied filesystem path.
        String path = Path.of(relative).normalize().toString().replace('\\', '/');
        return files.findByPath(digest, path).orElseThrow(ContentPages::invalidPage);
    }

    private byte[] bytes(byte[] digest, String relative) throws IOException {
        String sha = hex(file(digest, relative).blobSha256());
        try (var input = blobs.open(sha)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw invalidPage();
            // Approved immutable content must still match its registered address.
            try {
                if (!hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha)) throw invalidPage();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            return bytes;
        }
    }

    private String text(byte[] digest, JsonNode manifest, String field) throws IOException {
        if (!manifest.has(field)) return "";
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes(digest, manifest.path(field).asString()))).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) { throw invalidPage(); }
    }

    public Map<String, Object> page(String userId, String pageId, String version) throws IOException {
        byte[] digest = hash(pageId);
        if (!access.extension(userId, digest)) throw notFound();
        ExtensionPackageRow extension = extension(digest);
        if (!extension.version().equals(version)) throw notFound();
        JsonNode manifest = json.readTree(extension.manifest());
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("apiVersion", Map.of("major", 1, "minor", 5));
        page.put("html", text(digest, manifest, "entry"));
        page.put("entryPath", manifest.path("entry").asString());
        page.put("script", text(digest, manifest, "script"));
        page.put("style", text(digest, manifest, "style"));
        if (manifest.has("script")) page.put("scriptPath", manifest.path("script").asString());
        if (manifest.has("style")) page.put("stylePath", manifest.path("style").asString());
        SdkRegistry.Resolution resolution = sdk.resolve(manifest);
        page.put("dependencies", resolution.dependencies());
        if (resolution.contentApi() != null) page.put("contentApi", resolution.contentApi());
        page.put("answerStateDeclared", SdkRegistry.declares(manifest, "answer-state"));
        page.put("answerAreasDeclared", SdkRegistry.declares(manifest, "answer-areas"));
        if (SdkRegistry.declares(manifest, "answer-areas")) page.put("answerAreaVersion", "1.4.1");
        List<Map<String, Object>> assets = new ArrayList<>();
        long total = 0;
        for (JsonNode path : manifest.path("assets")) {
            String relative = path.asString();
            byte[] bytes = bytes(digest, relative);
            total += bytes.length;
            if (total > 8 * 1024 * 1024) throw invalidPage();
            String mime = MEDIA.get(relative.substring(relative.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
            if (mime == null) throw invalidPage();
            assets.add(Map.of("path", relative, "mime", mime, "base64", Base64.getEncoder().encodeToString(bytes)));
        }
        if (!assets.isEmpty()) page.put("assets", assets);
        return page;
    }

    public String sdk(String userId, String id, String version, boolean editor) {
        if (!(id.equals("quizforge.richtext") && List.of("1.0.0", "1.1.3").contains(version))
                && !(id.equals("quizforge.answer-area") && version.equals("1.4.1") && !editor)) throw notFound();
        boolean allowed = access.manifests(userId).stream().map(json::readTree).anyMatch(manifest -> sdk.permits(manifest, id, version));
        if (!allowed) throw notFound();
        return "/_qf_sdk/" + id + "/" + version + (editor ? "-editor" : "") + ".json";
    }

    public record FileResponse(String redirect, String mime) {}

    public FileResponse listening(String userId, String bankId, String path) throws IOException {
        String sha = access.assetPath(userId, bankId, path).orElseThrow(ContentPages::notFound);
        String lower = path.toLowerCase(Locale.ROOT);
        String mime = lower.endsWith(".mp3") ? "audio/mpeg" : lower.endsWith(".m4a") ? "audio/mp4" : null;
        if (mime == null || !blobs.exists(sha)) throw notFound();
        return new FileResponse("/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha, mime);
    }

    public FileResponse resource(String userId, String sha) throws IOException {
        String path = access.asset(userId, hash(sha)).orElseThrow(ContentPages::notFound);
        String suffix = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = MEDIA.get(suffix);
        if (mime == null || !List.of("png", "jpg", "jpeg", "webp", "gif").contains(suffix)) throw notFound();
        if (!blobs.exists(sha)) throw notFound();
        return new FileResponse("/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha, mime);
    }

    public static byte[] hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw notFound();
        return HexFormat.of().parseHex(value);
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "内容不存在或无权访问"); }
    private static ApiException invalidPage() { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_EXTENSION", "题型页面文件无效或不完整"); }
}
