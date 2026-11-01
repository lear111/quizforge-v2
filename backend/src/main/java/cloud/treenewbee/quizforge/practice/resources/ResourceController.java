package cloud.treenewbee.quizforge.practice.resources;

import java.io.IOException;
import java.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class ResourceController {
    private final ImageUploads uploads;
    private final JsonMapper json;
    public ResourceController(ImageUploads uploads, JsonMapper json) { this.uploads = uploads; this.json = json; }

    @PostMapping(value = "/api/resources", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImageUploads.Uploaded> upload(Principal principal, HttpServletRequest request,
            @RequestHeader(value = "X-QuizForge-Transient-Resource", required = false) String transientFlag) throws IOException {
        if (request.getContentLengthLong() > ImageUploads.MAX_BODY_BYTES) throw ImageUploads.tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(ImageUploads.MAX_BODY_BYTES + 1);
        if (bytes.length > ImageUploads.MAX_BODY_BYTES) throw ImageUploads.tooLarge();
        JsonNode body;
        try { body = json.readTree(bytes); }
        catch (RuntimeException malformed) { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "图片请求 JSON 无效"); }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(uploads.upload(principal.getName(), body, transientFlag));
    }
}
