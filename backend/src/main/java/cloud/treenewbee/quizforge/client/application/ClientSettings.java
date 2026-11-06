package cloud.treenewbee.quizforge.client.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 客户端兼容接口（遗留契约 C-05）的开关与下载预算；上线默认开启，默认值与旧平台一致。 */
@Component
public class ClientSettings {

    private final boolean connectEnabled;
    private final boolean localImportEnabled;
    private final long monthlyBudgetBytes;
    private final long ipDailyBudgetBytes;

    public ClientSettings(@Value("${qf.client.connect-enabled:true}") boolean connectEnabled,
                          @Value("${qf.client.local-import-enabled:true}") boolean localImportEnabled,
                          @Value("${qf.client.monthly-budget-bytes:150000000000}") long monthlyBudgetBytes,
                          @Value("${qf.client.ip-daily-budget-bytes:1048576000}") long ipDailyBudgetBytes) {
        this.connectEnabled = connectEnabled;
        this.localImportEnabled = localImportEnabled;
        this.monthlyBudgetBytes = monthlyBudgetBytes;
        this.ipDailyBudgetBytes = ipDailyBudgetBytes;
    }

    public boolean connectEnabled() {
        return connectEnabled;
    }

    public boolean localImportEnabled() {
        return localImportEnabled;
    }

    public long monthlyBudgetBytes() {
        return monthlyBudgetBytes;
    }

    public long ipDailyBudgetBytes() {
        return ipDailyBudgetBytes;
    }
}
