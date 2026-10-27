package cloud.treenewbee.quizforge.shared.web;

/**
 * Error body shared by every endpoint: {@code {"error":{"code":"...","message":"..."}}}.
 *
 * <p>The shape is inherited from the legacy API so the reused frontend keeps working
 * without changes (API design 1.3).
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Body(code, message));
    }
}
