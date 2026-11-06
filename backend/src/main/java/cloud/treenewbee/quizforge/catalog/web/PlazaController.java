package cloud.treenewbee.quizforge.catalog.web;

import cloud.treenewbee.quizforge.catalog.application.PlazaService;
import cloud.treenewbee.quizforge.practice.application.LibraryPracticeService;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlazaController {
    public record Import(@NotNull @Size(min=1,max=1000) List<String> banks,String group) {}
    private final PlazaService plaza;
    private final LibraryPracticeService practice;
    public PlazaController(PlazaService plaza,LibraryPracticeService practice) { this.plaza=plaza; this.practice=practice; }
    @GetMapping("/api/plaza/packs")
    public Object list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="latest") String sort,@RequestParam(required=false) String cursor) {
        return plaza.list(q,sort,cursor);
    }
    @GetMapping("/api/plaza/packs/{id}")
    public Object detail(Principal user,@PathVariable String id) { return plaza.detail(user==null?null:user.getName(),id); }
    @GetMapping("/api/plaza/packs/{id}/archive")
    public ResponseEntity<Void> download(@PathVariable String id) {
        var file=plaza.download(id);
        String sha=HexFormat.of().formatHex(file.blobSha256());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
            .header("Content-Disposition",ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff")
            .header("X-Accel-Redirect","/_qf_blobs/"+sha.substring(0,2)+"/"+sha.substring(2,4)+"/"+sha).build();
    }
    @PostMapping("/api/plaza/packs/{id}/imports")
    public Object imported(Principal user,@PathVariable String id,@Valid @RequestBody Import body) {
        plaza.visible(id);
        return Map.of("collections",practice.create(user.getName(),id,null,body.banks(),body.group(),false));
    }
    @GetMapping("/api/library/favorites")
    public Object favorites(Principal user,@RequestParam(required=false) String after) { return plaza.favorites(user.getName(),after); }
    @PutMapping("/api/plaza/packs/{id}/favorite")
    public ResponseEntity<Void> favorite(Principal user,@PathVariable String id) { plaza.favorite(user.getName(),id); return ResponseEntity.noContent().build(); }
    /** 收藏中的“有新版本”：确认已看到当前已审核版本。 */
    @PutMapping("/api/plaza/packs/{id}/favorite/seen")
    public ResponseEntity<Void> favoriteSeen(Principal user,@PathVariable String id) { plaza.favoriteSeen(user.getName(),id); return ResponseEntity.noContent().build(); }
    @DeleteMapping("/api/plaza/packs/{id}/favorite")
    public ResponseEntity<Void> unfavorite(Principal user,@PathVariable String id) { plaza.unfavorite(user.getName(),id); return ResponseEntity.noContent().build(); }
}
