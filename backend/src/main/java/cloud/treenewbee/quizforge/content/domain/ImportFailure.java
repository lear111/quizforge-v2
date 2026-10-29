package cloud.treenewbee.quizforge.content.domain;

/** Import outcome independent of HTTP. Messages are safe for the browser. */
public final class ImportFailure extends RuntimeException {
    private final String code;

    public ImportFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
