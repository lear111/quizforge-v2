package cloud.treenewbee.quizforge.exam;

import java.io.IOException;
import java.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.practice.application.PracticeSummaryService;
import cloud.treenewbee.quizforge.practice.resources.ResourceController;
import cloud.treenewbee.quizforge.practice.web.PracticeActionController;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
public class ExamController {
    public record Start(@NotBlank @Size(max = 64) String requestId, JsonNode durationMinutes) {
    }
    public record Submit(@NotBlank @Size(max = 64) String requestId) {
    }
    private final ExamService exams;
    private final PracticeReadService reads;
    private final PracticeActionController actions;
    private final PracticeSummaryService summaries;
    private final ContentPages pages;
    private final ResourceController resources;
    private final JsonMapper json;

    public ExamController(ExamService exams, PracticeReadService reads, PracticeActionController actions,
        PracticeSummaryService summaries, ContentPages pages, ResourceController resources, JsonMapper json) {
        this.exams = exams;
        this.reads = reads;
        this.actions = actions;
        this.summaries = summaries;
        this.pages = pages;
        this.resources = resources;
        this.json = json;
    }

    @PostMapping("/api/collections/bank/{id}/exams")
    public ResponseEntity<?> start(Principal principal, @PathVariable String id, @Valid @RequestBody Start body) {
        var duration = body.durationMinutes();
        if (duration != null && !duration.isNull() && (!duration.isIntegralNumber() || !duration.canConvertToInt()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "考试时长应为整数分钟，或不限时");
        return ok(exams.start(principal.getName(), id, body.requestId(), duration == null || duration.isNull() ? null : duration.intValue()));
    }

    @GetMapping("/api/collections/bank/{id}/exams")
    public ResponseEntity<?> list(Principal principal, @PathVariable String id) {
        return ok(exams.list(principal.getName(), id));
    }

    @GetMapping("/api/collections/exam/{id}/exam")
    public ResponseEntity<?> status(Principal principal, @PathVariable String id) {
        return ok(exams.status(principal.getName(), id));
    }

    @PostMapping("/api/collections/exam/{id}/exam")
    public ResponseEntity<?> submit(Principal principal, @PathVariable String id, @Valid @RequestBody Submit body) {
        return ok(exams.submit(principal.getName(), id, body.requestId()));
    }

    @DeleteMapping("/api/collections/exam/{id}/exam")
    public ResponseEntity<?> delete(Principal principal, @PathVariable String id) {
        return ok(exams.delete(principal.getName(), id));
    }

    @GetMapping("/api/collections/exam/{id}")
    public ResponseEntity<?> overview(Principal principal, @PathVariable String id) {
        var exam = exams.observe(principal.getName(), id);
        var value = (ObjectNode)json.valueToTree(reads.overview(principal.getName(), id));
        value.put("kind", "exam").put("readOnly", !exam.status().equals("in_progress"));
        value.set("exam", exams.metadata(exam));
        return ok(value);
    }

    @GetMapping("/api/collections/exam/{id}/questions/{qid}")
    public ResponseEntity<?> question(Principal principal, @PathVariable String id, @PathVariable String qid) {
        var exam = exams.observe(principal.getName(), id);
        var value = (ObjectNode)json.valueToTree(reads.question(principal.getName(), id, qid, null));
        var capabilities = (ObjectNode)value.path("capabilities");
        boolean active = exam.status().equals("in_progress");
        capabilities.put("canSubmit", false).put("canRetry", false).put("canSave", active)
            .put("canWhiteboard", active && capabilities.path("canWhiteboard").asBoolean())
            .put("canReview", !active && capabilities.path("canReview").asBoolean());
        return ok(value);
    }

    @PostMapping("/api/collections/exam/{id}/questions/{qid}/actions")
    public ResponseEntity<?> action(Principal principal, @PathVariable String id, @PathVariable String qid,
        @Valid @RequestBody PracticeActionController.Action body) {
        if (java.util.Set.of("draft", "whiteboard").contains(body.action()))
            return exams.write(principal.getName(), id, () -> actions.dispatch(principal, id, qid, body));
        var exam = exams.observe(principal.getName(), id);
        if ("review".equals(body.action()) && !exam.status().equals("in_progress"))
            return actions.dispatch(principal, id, qid, body);
        if ("ai".equals(body.action()) && !exam.status().equals("in_progress"))
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "FEATURE_NOT_AVAILABLE", "考试 AI 评分尚未开放");
        throw new ApiException(HttpStatus.CONFLICT, "EXAM_ACTION_FORBIDDEN", "考试只能保存草稿与白板，交卷后才能人工评分");
    }

    @GetMapping("/api/collections/exam/{id}/questions/{qid}/page")
    public ResponseEntity<?> page(Principal principal, @PathVariable String id, @PathVariable String qid) throws IOException {
        exams.observe(principal.getName(), id);
        var target = reads.resolveTarget(principal.getName(), id, qid);
        var extension = pages.extension(target.extensionDigest());
        return ok(pages.page(principal.getName(), java.util.HexFormat.of().formatHex(extension.digest()), extension.version()));
    }

    @GetMapping("/api/collections/exam/{id}/summary")
    public ResponseEntity<?> summary(Principal principal, @PathVariable String id) {
        var exam = exams.observe(principal.getName(), id);
        if (exam.status().equals("in_progress")) throw new ApiException(HttpStatus.CONFLICT, "EXAM_LOCKED", "交卷前不开放评分汇总");
        var summary = summaries.summary(principal.getName(), id);
        summary.put("canFinish", false).put("canRestart", false).put("finished", exam.status().equals("completed"));
        return ok(summary);
    }

    @GetMapping("/api/collections/exam/{id}/resources/{sha}")
    public ResponseEntity<?> resource(Principal principal, @PathVariable String id, @PathVariable String sha) throws IOException {
        exams.observe(principal.getName(), id);
        var file = pages.resource(principal.getName(), sha);
        return ResponseEntity.ok().header("Content-Type", file.mime()).header("X-Accel-Redirect", file.redirect())
            .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").build();
    }

    @PostMapping("/api/collections/exam/{id}/resources")
    public ResponseEntity<?> upload(Principal principal, @PathVariable String id, HttpServletRequest request,
        @RequestHeader(value = "X-QuizForge-Transient-Resource", required = false) String transientFlag) {
        return exams.write(principal.getName(), id, () -> {
            try {
                return resources.upload(principal, request, transientFlag);
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

    @GetMapping("/api/collections/exam/{id}/listening/{materialId}")
    public ResponseEntity<?> listening(Principal principal, @PathVariable String id, @PathVariable String materialId) throws IOException {
        exams.observe(principal.getName(), id);
        var file = reads.listening(principal.getName(), id, materialId);
        return ResponseEntity.ok().header("Content-Type", file.mime()).header("X-Accel-Redirect", file.redirect())
            .header("Cache-Control", "private, no-store").build();
    }

    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
}
