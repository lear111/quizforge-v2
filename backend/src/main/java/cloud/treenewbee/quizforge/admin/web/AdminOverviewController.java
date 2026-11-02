package cloud.treenewbee.quizforge.admin.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.treenewbee.quizforge.activity.application.ActivityTracker;
import cloud.treenewbee.quizforge.activity.infrastructure.ActivityMapper;
import cloud.treenewbee.quizforge.identity.application.AccountAdminService;
import cloud.treenewbee.quizforge.identity.infrastructure.UserAdminMapper;
import cloud.treenewbee.quizforge.quota.application.QuotaService;

/**
 * 后台概况：用户总数、近 7 天新注册、当前在线、存储总用量，以及近 7 天（北京时间）的注册与活跃趋势。
 * 题库相关的统计等题库广场完成后再加（响应中 banks 留空）。
 */
@RestController
public class AdminOverviewController {

    static final int DAYS = 7;

    public record Users(long total, long newLast7Days) {
    }

    public record Day(LocalDate day, long registered, long active, long practiced) {
    }

    /** online 只统计本实例（ActivityTracker 的单实例限制）。 */
    public record Overview(Users users, long online, long storageUsedBytes, List<Day> trend, Object banks) {
    }

    private final AccountAdminService accounts;
    private final ActivityTracker activity;
    private final QuotaService quotas;
    private final Clock clock;

    public AdminOverviewController(AccountAdminService accounts, ActivityTracker activity, QuotaService quotas,
                                   Clock clock) {
        this.accounts = accounts;
        this.activity = activity;
        this.quotas = quotas;
        this.clock = clock;
    }

    @GetMapping("/api/admin/overview")
    public ResponseEntity<Overview> overview() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ActivityTracker.ZONE);
        LocalDate first = today.minusDays(DAYS - 1);
        Map<LocalDate, Long> registered = new HashMap<>();
        // 窗口 = 北京时间 [6 天前 0 点, 明天 0 点)，与趋势中的 7 个日期完全对应。
        var from = first.atStartOfDay(ActivityTracker.ZONE).toInstant();
        var to = today.plusDays(1).atStartOfDay(ActivityTracker.ZONE).toInstant();
        for (UserAdminMapper.DailyRegistrations row : accounts.registrationsBetween(from, to)) {
            registered.put(row.day(), row.registered());
        }
        Map<LocalDate, ActivityMapper.DailyCount> active = new HashMap<>();
        for (ActivityMapper.DailyCount row : activity.daily(first)) active.put(row.day(), row);
        List<Day> trend = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            var counts = active.get(day);
            trend.add(new Day(day, registered.getOrDefault(day, 0L), counts == null ? 0 : counts.active(),
                    counts == null ? 0 : counts.practiced()));
        }
        long newUsers = trend.stream().mapToLong(Day::registered).sum();
        var body = new Overview(new Users(accounts.userCount(), newUsers), activity.online(),
                quotas.totalStorageUsed(), trend, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
