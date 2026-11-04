package cloud.treenewbee.quizforge.library.web;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import cloud.treenewbee.quizforge.catalog.application.PackInfo;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.library.application.MyLibraryService;
import cloud.treenewbee.quizforge.practice.application.LibraryPracticeService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MyLibraryController {
    public record Practice(Integer revision, @NotNull @Size(min = 1, max = 1000) List<String> banks, String group) {}
    public record Publish(int revision, boolean rights, String title, String summary, String description,
            String category, String tags) {}
    private final MyLibraryService library;
    private final LibraryPracticeService practice;
    private final ImportUploadService uploads;

    public MyLibraryController(MyLibraryService library, LibraryPracticeService practice, ImportUploadService uploads) {
        this.library = library;
        this.practice = practice;
        this.uploads = uploads;
    }

    @GetMapping("/api/library/packs")
    public ResponseEntity<?> mine(Principal user, @RequestParam(required = false) String after) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", library.mine(user.getName(), after)));
    }

    @GetMapping("/api/library/packs/{id}")
    public ResponseEntity<?> detail(Principal user, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(library.detail(user.getName(), id));
    }

    @GetMapping("/api/library/packs/{id}/revisions/{revision}/archive")
    public ResponseEntity<Void> download(Principal user, @PathVariable String id, @PathVariable int revision) {
        var file = library.original(user.getName(), id, revision);
        String sha = HexFormat.of().formatHex(file.blobSha256());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Accel-Redirect", "/_qf_blobs/" + sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha)
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/library/packs/{id}/practice")
    public Map<String, Object> practice(Principal user, @PathVariable String id, @Valid @RequestBody Practice body) {
        return Map.of("collections", practice.create(user.getName(), id, body.revision(), body.banks(), body.group(), true));
    }

    @PostMapping("/api/library/packs/{id}/publish")
    public ResponseEntity<?> publish(Principal user, @PathVariable String id, @RequestBody Publish body) {
        if (!body.rights()) throw new ApiException(HttpStatus.BAD_REQUEST, "PACK_INFO_INVALID", "请确认你有权分享这些题库");
        var info = PackInfo.of(body.title(), body.summary(), body.description(), body.category(), body.tags(), "");
        return ResponseEntity.accepted().body(uploads.publishPrivate(user.getName(), id, body.revision(), info));
    }
}
