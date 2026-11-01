package cloud.treenewbee.quizforge.migration;

/** 无法继续执行的错误；code 与报告中的发现代码一致。 */
public class MigrationException extends RuntimeException {

    private final String code;

    public MigrationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
