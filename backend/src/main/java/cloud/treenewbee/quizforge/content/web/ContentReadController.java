package cloud.treenewbee.quizforge.content.web;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.content.application.ContentPages;

@RestController
public class ContentReadController {
    private final ContentPages pages;
    public ContentReadController(ContentPages pages) { this.pages = pages; }

    @GetMapping("/api/extensions/{pageId}/{version}/page")
    public ResponseEntity<Map<String, Object>> page(Principal principal, @PathVariable String pageId, @PathVariable String version) throws IOException {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(pages.page(principal.getName(), pageId, version));
    }

    @GetMapping({"/api/sdk/{id}/{version}", "/api/sdk/{id}/{version}/editor"})
    public ResponseEntity<Void> sdk(Principal principal, @PathVariable String id, @PathVariable String version,
            jakarta.servlet.http.HttpServletRequest request) {
        boolean editor = request.getRequestURI().endsWith("/editor");
        return ResponseEntity.ok().header("Content-Type", "application/json; charset=UTF-8")
                .header("Cache-Control", "private, no-store")
                .header("X-Accel-Redirect", pages.sdk(principal.getName(), id, version, editor)).build();
    }

    @GetMapping("/api/resources/{sha256}")
    public ResponseEntity<Void> resource(Principal principal, @PathVariable String sha256) throws IOException {
        var file = pages.resource(principal.getName(), sha256);
        return ResponseEntity.ok().header("Content-Type", file.mime()).header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").header("X-Accel-Redirect", file.redirect()).build();
    }
}
