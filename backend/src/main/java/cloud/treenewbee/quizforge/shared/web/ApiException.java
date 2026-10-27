package cloud.treenewbee.quizforge.shared.web;

import org.springframework.http.HttpStatus;

/**
 * A failure that is reported to the client with a stable error code.
 *
 * <p>The message must be safe to show to end users: no stack traces, SQL or internal paths
 * (API design 1.3).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
