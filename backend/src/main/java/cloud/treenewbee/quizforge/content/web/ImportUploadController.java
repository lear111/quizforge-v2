package cloud.treenewbee.quizforge.content.web;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.content.infrastructure.ImportCatalogMapper;
import cloud.treenewbee.quizforge.content.infrastructure.ImportQueueMapper;
import jakarta.servlet.http.HttpServletRequest;

/** 原文件接收协议；主页入口在下一切片接入，不经过浏览器解包或校验。 */
@RestController
public class ImportUploadController {
    private final ImportUploadService uploads;
    private final ImportCatalogMapper metadata;
    private final ImportQueueMapper jobs;
    private final cloud.treenewbee.quizforge.content.application.ImportQueueJobs worker;
    private final cloud.treenewbee.quizforge.admin.infrastructure.ImportOverviewMapper overview;

    public ImportUploadController(ImportUploadService uploads, ImportCatalogMapper metadata, ImportQueueMapper jobs,
            cloud.treenewbee.quizforge.content.application.ImportQueueJobs worker,
            cloud.treenewbee.quizforge.admin.infrastructure.ImportOverviewMapper overview) {
        this.uploads = uploads;
        this.metadata = metadata;
        this.jobs = jobs;
        this.worker = worker;
        this.overview = overview;
    }

    @PostMapping(value = "/api/library/uploads", consumes = "application/octet-stream")
    public ResponseEntity<ImportUploadService.Accepted> upload(Principal user, @RequestParam String filename,
            @RequestParam(required = false) String packId, HttpServletRequest request) throws IOException {
        long started = System.nanoTime();
        var accepted = uploads.receivePrivate(user.getName(), packId, filename, request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.accepted().header("Server-Timing",
                "upload;dur=" + (System.nanoTime() - started) / 1_000_000).body(accepted);
    }

    @GetMapping("/api/library/uploads/{id}/revisions/{revision}")
    public Map<String, Object> state(Principal user, @PathVariable String id, @PathVariable int revision) {
        metadata.owned(id, user.getName(), revision).orElseThrow(() -> new cloud.treenewbee.quizforge.shared.web.ApiException(
                HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "题库包不存在或已删除"));
        var state = uploads.reviewState(user.getName(), id, revision);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("packId", id);
        result.put("revision", revision);
        result.put("status", state.status());
        result.put("reason", state.reason());
        return result;
    }

    @DeleteMapping("/api/library/uploads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal user, @PathVariable String id) { uploads.delete(user.getName(), id); }

    @GetMapping("/api/admin/import-jobs")
    public Map<String, Object> queue() { return Map.of("items", jobs.active(), "counts", overview.queue()); }

    @PostMapping("/api/admin/import-jobs/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable String id) { worker.retry(id); }
}
