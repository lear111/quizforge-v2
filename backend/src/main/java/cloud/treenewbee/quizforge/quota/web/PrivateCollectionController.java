package cloud.treenewbee.quizforge.quota.web;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.quota.application.PrivateCollectionService;

@RestController
public class PrivateCollectionController {
    private final PrivateCollectionService collections;
    public PrivateCollectionController(PrivateCollectionService collections) { this.collections = collections; }
    @DeleteMapping("/api/collections/bank/{id}")
    public ResponseEntity<Void> delete(Principal user, @PathVariable String id) {
        collections.delete(user.getName(), id); return ResponseEntity.noContent().build();
    }
}
