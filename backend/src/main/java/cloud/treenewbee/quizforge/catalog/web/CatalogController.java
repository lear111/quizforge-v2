package cloud.treenewbee.quizforge.catalog.web;

import java.io.IOException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cloud.treenewbee.quizforge.catalog.application.CatalogPublishService;
import cloud.treenewbee.quizforge.catalog.application.CatalogPublishService.Screenshot;
import cloud.treenewbee.quizforge.catalog.application.PackInfo;
import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发布者的题库广场接口：从本地上传发布、上传整包新版本、删除整个包、我的发布、截图读取。
 * 用户身份只取自登录会话；归属检查在 SQL 条件里。
 */
@RestController
public class CatalogController {

    public record Join(@NotNull @Size(min = 1, max = 1000) List<String> banks) {
    }

    private final CatalogPublishService publishing;
    private final ImportService imports;
    private final AccountAdminService admins;

    public CatalogController(CatalogPublishService publishing, ImportService imports, AccountAdminService admins) {
        this.publishing = publishing;
        this.imports = imports;
        this.admins = admins;
    }

    @GetMapping("/api/catalog/categories")
    public Map<String, Object> categories() {
        return Map.of("categories", PackInfo.CATEGORIES, "maxScreenshots", PackInfo.MAX_SCREENSHOTS,
                "maxScreenshotBytes", PackInfo.MAX_SCREENSHOT_BYTES, "maxArchiveBytes", ImportService.MAX_UPLOAD_BYTES);
    }

    /** 从本地上传 .qbank 发布。 */
    @PostMapping(value = "/api/catalog/packs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CatalogPublishService.Submitted> publish(Principal user,
            @RequestPart("archive") MultipartFile archive,
            @RequestPart(value = "screenshots", required = false) List<MultipartFile> screenshots,
            @RequestParam Map<String, String> fields) throws IOException {
        PackInfo info = info(fields);
        Path temporary = spool(archive);
        try {
            return created(publishing.publishUpload(user.getName(), info, rights(fields), temporary,
                    archive.getOriginalFilename(), shots(screenshots)));
        } finally { imports.delete(temporary); }
    }

    /** 上传整包新版本。keepScreenshots 为沿用的上一版本截图 ID（可重复）。 */
    @PostMapping(value = "/api/catalog/packs/{id}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CatalogPublishService.Submitted> revise(Principal user, @PathVariable String id,
            @RequestPart("archive") MultipartFile archive,
            @RequestPart(value = "screenshots", required = false) List<MultipartFile> screenshots,
            @RequestParam(value = "keepScreenshots", required = false) List<String> keep,
            @RequestParam Map<String, String> fields) throws IOException {
        PackInfo info = info(fields);
        Path temporary = spool(archive);
        try {
            return created(publishing.uploadRevision(user.getName(), id, info, rights(fields), temporary,
                    archive.getOriginalFilename(), shots(screenshots), keep));
        } finally { imports.delete(temporary); }
    }

    @DeleteMapping("/api/catalog/packs/{id}")
    public ResponseEntity<Void> delete(Principal user, @PathVariable String id) {
        publishing.delete(user.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/catalog/my-packs")
    public ResponseEntity<Map<String, Object>> mine(Principal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", publishing.mine(user.getName())));
    }

    @GetMapping("/api/catalog/files/{id}")
    public ResponseEntity<Void> file(Principal user, @PathVariable String id) {
        var file = publishing.file(user.getName(), admins.isAdmin(user.getName()), id);
        String sha = HexFormat.of().formatHex(file.blobSha256());
        return ResponseEntity.ok().header("Content-Type", file.mime()).header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Accel-Redirect", "/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha).build();
    }

    /** 从当前已审核的公开版本创建独立练习实例。 */
    @PostMapping("/api/catalog/packs/{id}/practice")
    public CatalogPublishService.Joined join(Principal user, @PathVariable String id, @Valid @RequestBody Join body) {
        return publishing.join(user.getName(), id, body.banks());
    }

    // ---------------------------------------------------------------- 内部

    private Path spool(MultipartFile archive) throws IOException {
        String name = archive.getOriginalFilename() == null ? "" : archive.getOriginalFilename();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".qbank")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIBRARY_ARCHIVE", "请上传 .qbank 题库包");
        }
        try (var input = archive.getInputStream()) {
            return imports.spool(input);
        }
    }

    private static List<Screenshot> shots(List<MultipartFile> files) throws IOException {
        List<Screenshot> shots = new ArrayList<>();
        if (files == null) return shots;
        if (files.size() > PackInfo.MAX_SCREENSHOTS) throw new ApiException(HttpStatus.BAD_REQUEST, "PACK_INFO_INVALID", "截图最多 6 张");
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            if (file.getSize() > PackInfo.MAX_SCREENSHOT_BYTES) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "PACK_INFO_INVALID", "每张截图不能超过 3 MiB");
            }
            shots.add(new Screenshot(file.getOriginalFilename(), file.getBytes()));
        }
        return shots;
    }

    private static PackInfo info(Map<String, String> fields) {
        return PackInfo.of(fields.get("title"), fields.get("summary"), fields.get("description"), fields.get("category"),
                fields.get("tags"), fields.get("releaseNotes"));
    }

    private static boolean rights(Map<String, String> fields) {
        return "yes".equals(fields.get("rights"));
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
