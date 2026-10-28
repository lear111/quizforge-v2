package cloud.treenewbee.quizforge.content.archive;

/**
* .qbank 压缩包被拒绝。INVALID 表示内容不合法（对外 422），LIMIT 表示超过大小或数量上限（对外 413）。
* 不直接抛 ApiException：archive 包属于领域层，不依赖 HTTP。
*/
public class ArchiveRejectedException extends RuntimeException {

    public enum Reason {
        INVALID, LIMIT
    }

    private final Reason reason;

    public Reason reason() {
        return reason;
    }

    private ArchiveRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static ArchiveRejectedException invalid(String message) {
        return new ArchiveRejectedException(Reason.INVALID, message);
    }

    public static ArchiveRejectedException limit(String message) {
        return new ArchiveRejectedException(Reason.LIMIT, message);
    }

}