package cloud.treenewbee.quizforge.shared.web;

import java.util.UUID;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Converts exceptions into the shared error format.
 *
 * <p>Unexpected errors are logged with a request ID and returned as a generic
 * {@code INTERNAL_ERROR}; details never reach the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException error) {
        return ResponseEntity.status(error.status()).body(ErrorResponse.of(error.code(), error.getMessage()));
    }

    @ExceptionHandler(RuleRunnerException.class)
    ResponseEntity<ErrorResponse> handleRuleUnavailable(RuleRunnerException error) {
        log.warn("Rule service unavailable", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("RULE_UNAVAILABLE", "规则服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", "请求内容不符合要求"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ErrorResponse> handleInvalidBody(Exception error) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", "请求内容不符合要求"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("NOT_FOUND", "资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("METHOD_NOT_ALLOWED", "请求方法不受支持"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception error) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unhandled error, requestId={}", requestId, error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Request-Id", requestId)
                .body(ErrorResponse.of("INTERNAL_ERROR", "服务器暂时无法处理，请稍后重试"));
    }
}
