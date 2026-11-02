package cloud.treenewbee.quizforge.quota.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import cloud.treenewbee.quizforge.admin.application.AdminAuditService;
import cloud.treenewbee.quizforge.quota.infrastructure.QuotaMapper;
import cloud.treenewbee.quizforge.quota.infrastructure.QuotaMapper.Account;
import cloud.treenewbee.quizforge.identity.infrastructure.UserMapper;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** All mutations participate in the caller's business transaction. Never wrap external I/O in this lock. */
@Service
public class QuotaService {
    public static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    private final QuotaMapper mapper;
    private final UserMapper users;
    private final Clock clock;
    private final JsonMapper json;
    private final AdminAuditService audit;
    public QuotaService(QuotaMapper mapper, UserMapper users, Clock clock, JsonMapper json, AdminAuditService audit) {
        this.mapper = mapper; this.users = users; this.clock = clock; this.json = json; this.audit = audit;
    }
    public record Amount(long usedBytes, long limitBytes, Long overrideBytes) {}
    public record Usage(Amount storage, Amount dailyDownload, LocalDate day, Instant resetsAt) {}
    public record StorageItem(String category, String objectId, String collectionId, long bytes) {}

    private Account lock(String userId) {
        mapper.initialize(userId);
        return mapper.lock(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "用户不存在"));
    }

    @Transactional
    public Usage usage(String userId) { return view(lock(userId)); }

    /** 全站存储总用量（后台概况）。 */
    public long totalStorageUsed() { return mapper.totalStorageUsed(); }

    private Usage view(Account account) {
        var defaults = mapper.defaults();
        var day = LocalDate.ofInstant(clock.instant(), BILLING_ZONE);
        long downloadUsed = day.equals(account.downloadDay()) ? account.downloadUsed() : 0;
        return new Usage(new Amount(account.storageUsed(), account.storageLimit() == null ? defaults.storageBytes() : account.storageLimit(), account.storageLimit()),
                new Amount(downloadUsed, account.dailyDownloadLimit() == null ? defaults.dailyDownloadBytes() : account.dailyDownloadLimit(), account.dailyDownloadLimit()),
                day, day.plusDays(1).atStartOfDay(BILLING_ZONE).toInstant());
    }

    /** Replace an owned item's logical size; growth, shrinkage and retry use the same primitive. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setStorage(String userId, String category, String objectId, String collectionId, long bytes) {
        setStorageBatch(userId, List.of(new StorageItem(category, objectId, collectionId, bytes)));
    }

    /** Check the net change of one business operation, independent of item update order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setStorageBatch(String userId, List<StorageItem> items) {
        replaceStorage(userId, items, null, null);
    }

    /** Include rows deleted by restart in the same net-change check. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setStorageBatchAndClearPrefix(String userId, List<StorageItem> items, String category, String prefix) {
        java.util.Objects.requireNonNull(category);
        java.util.Objects.requireNonNull(prefix);
        replaceStorage(userId, items, category, prefix);
    }

    private void replaceStorage(String userId, List<StorageItem> items, String clearedCategory, String clearedPrefix) {
        var keys = new HashSet<List<String>>();
        for (var item : items) {
            if (item.bytes() < 0) throw new IllegalArgumentException("Negative storage size");
            if (!keys.add(List.of(item.category(), item.objectId()))) throw new IllegalArgumentException("Duplicate storage item");
            if (item.category().equals(clearedCategory) && item.objectId().startsWith(clearedPrefix))
                throw new IllegalArgumentException("Storage item overlaps cleared prefix");
        }
        var account = lock(userId);
        long delta = clearedCategory == null ? 0 : -mapper.prefixBytes(userId, clearedCategory, clearedPrefix).stream().reduce(0L, Math::addExact);
        for (var item : items) {
            long previous = mapper.item(userId, item.category(), item.objectId()).orElse(0L);
            delta = Math.addExact(delta, Math.subtractExact(item.bytes(), previous));
        }
        long limit = view(account).storage().limitBytes();
        if (delta > 0 && (account.storageUsed() > limit || delta > limit - account.storageUsed()))
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA_EXCEEDED", "存储空间不足，请删除不需要的私人题库或历史记录");
        mapper.storage(userId, Math.addExact(account.storageUsed(), delta));
        if (clearedCategory != null) mapper.deletePrefix(userId, clearedCategory, clearedPrefix);
        for (var item : items) mapper.putItem(userId, item.category(), item.objectId(), item.collectionId(), item.bytes());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCollection(String userId, String collectionId) {
        var account = lock(userId);
        long bytes = mapper.collectionBytes(userId, collectionId).stream().reduce(0L, Math::addExact);
        mapper.storage(userId, Math.subtractExact(account.storageUsed(), bytes));
        mapper.deleteCollection(userId, collectionId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releasePrefix(String userId, String category, String prefix) {
        var account = lock(userId);
        long bytes = mapper.prefixBytes(userId, category, prefix).stream().reduce(0L, Math::addExact);
        mapper.storage(userId, Math.subtractExact(account.storageUsed(), bytes));
        mapper.deletePrefix(userId, category, prefix);
    }

    /** Call after an export/file has been prepared, before authorizing its response; rollback on failure. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeDownload(String userId, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative download size");
        var account = lock(userId); var usage = view(account);
        long used = Math.addExact(usage.dailyDownload().usedBytes(), bytes);
        if (used > usage.dailyDownload().limitBytes()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DOWNLOAD_QUOTA_EXCEEDED", "今日下载流量已用完，请明天再试");
        mapper.download(userId, usage.day(), used);
    }

    @Transactional
    public Usage adjust(String actor, String userId, Long storage, Long download) {
        if (!users.findById(actor).map(user -> user.isAdmin()).orElse(false))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可以调整配额");
        if ((storage != null && storage < 0) || (download != null && download < 0))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "配额不能为负数");
        var previous = lock(userId);
        mapper.limits(userId, storage, download);
        mapper.audit(Ids.uuidV7(), actor, userId, limitsJson(previous.storageLimit(), previous.dailyDownloadLimit()), limitsJson(storage, download), clock.instant());
        // 管理员操作统一记入后台审计日志（quota_adjustment 仍保留配额历史）。
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("previous", limits(previous.storageLimit(), previous.dailyDownloadLimit()));
        detail.put("replacement", limits(storage, download));
        audit.record(actor, "quota", "quota-adjusted", userId, detail);
        return view(mapper.lock(userId).orElseThrow());
    }

    private static java.util.Map<String, Object> limits(Long storage, Long download) {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("storageBytes", storage);
        value.put("dailyDownloadBytes", download);
        return value;
    }

    private String limitsJson(Long storage, Long download) {
        var value = json.createObjectNode(); value.putPOJO("storageBytes", storage); value.putPOJO("dailyDownloadBytes", download);
        return json.writeValueAsString(value);
    }
}
