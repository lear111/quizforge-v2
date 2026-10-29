package cloud.treenewbee.quizforge.content.web;

import cloud.treenewbee.quizforge.content.archive.ArchiveRejectedException;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import cloud.treenewbee.quizforge.content.domain.PackageRejectedException;
import cloud.treenewbee.quizforge.shared.web.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ImportController.class)
public class ImportExceptionHandler {
    @ExceptionHandler(ArchiveRejectedException.class)
    ResponseEntity<ErrorResponse> archive(ArchiveRejectedException error) {
        return response(error.reason() == ArchiveRejectedException.Reason.LIMIT ? "LIBRARY_ARCHIVE_LIMIT" : "INVALID_LIBRARY_ARCHIVE", error.getMessage());
    }

    @ExceptionHandler(PackageRejectedException.class)
    ResponseEntity<ErrorResponse> structure(PackageRejectedException error) {
        return response(error.code().name(), error.getMessage());
    }

    @ExceptionHandler(ImportFailure.class)
    ResponseEntity<ErrorResponse> rules(ImportFailure error) { return response(error.code(), error.getMessage()); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> oversized(MaxUploadSizeExceededException error) {
        return response("LIBRARY_ARCHIVE_LIMIT", "题库包超过 128 MiB 上传上限");
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<ErrorResponse> multipart(Exception error) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", "请提供名为 file 的 multipart 文件"));
    }

    private ResponseEntity<ErrorResponse> response(String code, String message) {
        int status = code.equals("RULE_UNAVAILABLE") ? 503 : code.equals("LIBRARY_ARCHIVE_LIMIT") ? 413 : 422;
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
