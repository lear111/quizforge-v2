package cloud.treenewbee.quizforge.practice.grading;

import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.exam.ExamMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/collections/{kind:bank|exam}/{id}/grading")
public class BrowserGradingController {
    public record Report(@NotBlank String questionId, @NotBlank @Size(max=32) String generation,
            @NotNull JsonNode outcome) {}
    public record Batch(@NotNull @Size(min=1,max=20) List<@Valid Report> items) {}
    private final BrowserGradingService service;
    private final ExamMapper exams;
    private final JsonMapper json;
    private final Validator validator;
    private static final int MAX_BATCH_BYTES = 8 * 1024 * 1024;

    public BrowserGradingController(BrowserGradingService service, ExamMapper exams, JsonMapper json, Validator validator) {
        this.service = service; this.exams = exams; this.json = json; this.validator = validator;
    }

    @GetMapping("/pending")
    public ResponseEntity<?> pending(Principal principal, @PathVariable String kind, @PathVariable String id,
            @RequestParam(required=false) String questionId) {
        scope(principal.getName(), kind, id);
        return ok(service.pending(principal.getName(), id, questionId));
    }

    @PostMapping(value="/reports", consumes="application/json")
    public ResponseEntity<?> report(Principal principal, @PathVariable String kind, @PathVariable String id,
            HttpServletRequest request) throws IOException {
        scope(principal.getName(), kind, id);
        byte[] bytes = request.getInputStream().readNBytes(MAX_BATCH_BYTES + 1);
        // 有界读取也兼容 chunked；不在仍有大请求体发送时提前返回，避免响应被连接重置吞掉。
        if (request.getContentLengthLong() > MAX_BATCH_BYTES || bytes.length > MAX_BATCH_BYTES) throw tooLarge();
        Batch batch;
        try { batch = json.readValue(bytes, Batch.class); }
        catch (RuntimeException error) { throw invalid(); }
        if (batch == null || !validator.validate(batch).isEmpty()) throw invalid();
        // 单题短事务，部分成功可安全重放，单批不长时间锁住整卷。
        var values = json.createArrayNode();
        for (var item : batch.items()) {
            if (item == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADING_REPORT", "判分报告不能为空");
            values.add(service.report(principal.getName(), id, item.questionId(), item.generation(), item.outcome()));
        }
        return ok(json.createObjectNode().set("items", values));
    }

    private void scope(String user, String kind, String id) {
        if (kind.equals("exam") != exams.find(user, id).isPresent()) throw ContentPages.notFound();
    }

    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADING_REPORT", "判分报告格式无效，单批最多 20 道题");
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "GRADING_REPORT_TOO_LARGE", "判分报告超过 8 MiB 上限");
    }
}
