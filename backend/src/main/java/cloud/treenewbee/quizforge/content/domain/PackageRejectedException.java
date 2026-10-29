package cloud.treenewbee.quizforge.content.domain;

/** Package validation outcome; transport code maps these codes to its own status representation. */
public final class PackageRejectedException extends RuntimeException {
    public enum Code {
        UNSUPPORTED_LIBRARY_ARCHIVE, INVALID_LIBRARY_ARCHIVE, UNSUPPORTED_RESOURCE_TYPE,
        INVALID_QUESTION_BANK, INVALID_EXTENSION, LIBRARY_ARCHIVE_LIMIT
    }

    private final Code code;

    public PackageRejectedException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
