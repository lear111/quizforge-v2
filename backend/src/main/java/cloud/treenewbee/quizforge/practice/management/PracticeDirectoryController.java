package cloud.treenewbee.quizforge.practice.management;

import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PracticeDirectoryController {
    private final PracticeDirectoryService directory;

    public PracticeDirectoryController(PracticeDirectoryService directory) {
        this.directory = directory;
    }

    @PostMapping("/api/library/manage")
    public PracticeDirectoryService.Result manage(Principal user, @RequestBody PracticeDirectoryService.Change change) {
        return directory.manage(user.getName(), change);
    }
}
