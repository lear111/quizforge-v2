package cloud.treenewbee.quizforge.quota.web;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.library.LibraryService;

@RestController
public class PrivateCollectionController {
    private final LibraryService collections;
    public PrivateCollectionController(LibraryService collections) {
        this.collections = collections;
    }
    @DeleteMapping("/api/collections/bank/{id}")
    public ResponseEntity<Void> delete(Principal user, @PathVariable String id) {
        collections.deleteOne(user.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
