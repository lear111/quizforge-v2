package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.practice.infrastructure.PracticeContextRow;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.springframework.http.HttpStatus;

public final class PracticeWrites {
    private PracticeWrites() {}
    public static void requireActive(PracticeContextRow context) {
        if (!"active".equals(context.completionStatus()))
            throw new ApiException(HttpStatus.CONFLICT,"PRACTICE_LOCKED","本轮练习正在完成或已经完成，不能修改作答");
    }
    public static void requireStable(PracticeContextRow context) {
        if ("finishing".equals(context.completionStatus()) || "archiving".equals(context.completionStatus()))
            throw new ApiException(HttpStatus.CONFLICT,"PRACTICE_BUSY","本轮练习正在处理，请稍后重试");
    }
}
