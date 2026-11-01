package cloud.treenewbee.quizforge.quota.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    public QuotaService(QuotaMapper mapper, UserMapper users, Clock clock, JsonMapper json) {
        this.mapper = mapper; this.users = users; this.clock = clock; this.json = json;
    }
    public record Amount(long usedBytes, long limitBytes, Long overrideBytes) {}
    public record Usage(Amount storage, Amount dailyDownload, LocalDate day, Instant resetsAt) {}

    private Account lock(String userId) {
        mapper.initialize(userId);
        return mapper.lock(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "用户不存在"));
    }

    @Transactional
    public Usage usage(String userId) { return view(lock(userId)); }

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
        if (bytes < 0) throw new IllegalArgumentException("Negative storage size");
        var account = lock(userId);
        long previous = mapper.item(userId, category, objectId).orElse(0L);
        long delta = Math.subtractExact(bytes, previous);
        long used = Math.addExact(account.storageUsed(), delta);
        long limit = view(account).storage().limitBytes();
        if (delta > 0 && used > limit) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA_EXCEEDED", "存储空间不足，请删除不需要的私人题库或历史记录");
        mapper.storage(userId, used);
        mapper.putItem(userId, category, objectId, collectionId, bytes);
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
        return view(mapper.lock(userId).orElseThrow());
    }

    private String limitsJson(Long storage, Long download) {
        var value = json.createObjectNode(); value.putPOJO("storageBytes", storage); value.putPOJO("dailyDownloadBytes", download);
        return json.writeValueAsString(value);
    }
}
