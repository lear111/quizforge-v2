package cloud.treenewbee.quizforge.quota.web;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.practice.management.PracticeDirectoryService;

@RestController
public class PrivateCollectionController {
    private final PracticeDirectoryService collections;
    public PrivateCollectionController(PracticeDirectoryService collections) {
        this.collections = collections;
    }
    @DeleteMapping("/api/collections/bank/{id}")
    public ResponseEntity<Void> delete(Principal user, @PathVariable String id) {
        collections.deleteOne(user.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
