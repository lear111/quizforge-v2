package cloud.treenewbee.quizforge.library;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class LibraryController {
    private final LibraryService library;
    private final LibraryExportService exports;

    public LibraryController(LibraryService library, LibraryExportService exports) {
        this.library = library;
        this.exports = exports;
    }

    @PostMapping("/api/library/manage")
    public LibraryService.Result manage(Principal user, @RequestBody LibraryService.Change change) {
        return library.manage(user.getName(), change);
    }

    @PostMapping("/api/library/export")
    public ResponseEntity<Void> export(Principal user, @RequestBody LibraryExportService.Request request) {
        var download = exports.export(user.getName(), request);
        return ResponseEntity.ok()
                .header("X-Accel-Redirect", "/_qf_exports/" + download.path().getFileName())
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-QF-Export-Warning", java.net.URLEncoder.encode(String.join("；", download.warnings()), StandardCharsets.UTF_8))
                .build();
    }
}
