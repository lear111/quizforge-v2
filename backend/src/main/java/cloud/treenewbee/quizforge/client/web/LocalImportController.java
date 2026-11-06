package cloud.treenewbee.quizforge.client.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cloud.treenewbee.quizforge.client.application.ClientSettings;
import cloud.treenewbee.quizforge.client.application.LocalImportService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 固定版本导入与按版本下载（遗留契约 C-05 第 10–12 节）。匿名访问；文件由 Nginx 通过 X-Accel-Redirect 传输，
 * 返回审核通过版本的原始文件，逐字节一致。客户端不跟随重定向，内部跳转对它不可见。
 */
@RestController
public class LocalImportController {

    private final LocalImportService imports;
    private final ClientSettings settings;

    public LocalImportController(LocalImportService imports, ClientSettings settings) {
        this.imports = imports;
        this.settings = settings;
    }

    @RequestMapping("/api/packs/{id}/local-import")
    public ResponseEntity<Map<String, Object>> manifest(@PathVariable String id, HttpServletRequest request) {
        var archive = imports.manifest(id, request.getMethod(), request.getParameterMap(), request.getQueryString(), request.getRemoteAddr());
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("id", archive.packId());
        pack.put("revision", archive.revision());
        pack.put("title", archive.title());
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", archive.name());
        file.put("size", archive.size());
        file.put("sha256", archive.sha256());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("pack", pack);
        value.put("archive", file);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(value);
    }

    @GetMapping("/api/packs/{id}/download")
    public ResponseEntity<Void> download(@PathVariable String id, HttpServletRequest request) {
        var archive = imports.download(id, request.getMethod(), request.getParameterMap(), request.getQueryString(), request.getRemoteAddr());
        String sha = archive.sha256();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("Content-Disposition", "attachment; filename=\"question-bank.qbank\"; filename*=UTF-8''"
                        + URLEncoder.encode(archive.name(), StandardCharsets.UTF_8).replace("+", "%20"))
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Accel-Redirect", "/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha)
                .build();
    }

    /** 旧 Hub 的 /api/java/capabilities（只有旧 Hub 网页使用），内容反映 V2 已实现的能力。 */
    @GetMapping("/api/java/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("phase", "v2");
        value.put("practiceOrigin", "");
        value.put("legacyOrigin", "");
        value.put("localImportEnabled", settings.localImportEnabled());
        value.put("native", List.of("catalog", "search", "detail", "session-read", "auth-write", "verification", "devices",
                "attachments", "installers", "publishing", "review", "admin-management", "review-notifications", "ai-balance",
                "ai-gateway", "local-import"));
        value.put("pending", List.of());
        return value;
    }

    @RestControllerAdvice(assignableTypes = LocalImportController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class Errors {
        @ExceptionHandler(LocalImportService.Failure.class)
        ResponseEntity<Map<String, String>> failed(LocalImportService.Failure error) {
            return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", error.getMessage(), "code", error.code()));
        }
    }
}
