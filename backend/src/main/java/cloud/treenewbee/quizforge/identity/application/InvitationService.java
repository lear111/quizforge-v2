package cloud.treenewbee.quizforge.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.identity.infrastructure.InvitationMapper;
import cloud.treenewbee.quizforge.identity.infrastructure.InvitationMapper.InvitationRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/**
 * 邀请码（HUB_INVITATION_CLOUD.md）：128 位随机值，格式 QF-XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX；
 * 数据库只存摘要与识别前缀，完整码只在生成时返回一次。每码只能注册一个账号；已使用的码不能停用。
 * 关闭“注册需要邀请码”不会删除或消费已生成的码。
 */
@Service
public class InvitationService {

    static final int MAX_COUNT = 100;
    static final int MAX_DAYS = 365;
    private static final int LIST_LIMIT = 1000;
    private static final Pattern FORMAT = Pattern.compile("QF-(?:[A-F0-9]{8}-){3}[A-F0-9]{8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String id, String code, Instant expiresAt) {
    }

    public record Item(String id, String prefix, String state, Instant createdAt, Instant expiresAt,
                       Instant revokedAt, Instant usedAt, UsedBy usedBy) {
    }

    public record UsedBy(String id, String name, String contact) {
    }

    private final InvitationMapper mapper;
    private final AccountAuditService audit;
    private final Clock clock;

    public InvitationService(InvitationMapper mapper, AccountAuditService audit, Clock clock) {
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public List<Issued> create(String actorId, int count, int days) {
        if (count < 1 || count > MAX_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "每次生成 1–100 个邀请码");
        }
        if (days < 1 || days > MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "有效期须为 1–365 天");
        }
        Instant now = clock.instant();
        Instant expires = now.plus(Duration.ofDays(days));
        List<Issued> issued = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] entropy = new byte[16];
            RANDOM.nextBytes(entropy);
            String body = HexFormat.of().withUpperCase().formatHex(entropy);
            String code = "QF-" + String.join("-", body.substring(0, 8), body.substring(8, 16),
                    body.substring(16, 24), body.substring(24));
            String id = Ids.uuidV7();
            mapper.insert(id, digest(code), code.substring(0, 11), actorId, now, expires);
            issued.add(new Issued(id, code, expires));
        }
        audit.record(actorId, "invitations", "invitation-created", Map.of("count", count, "days", days));
        return issued;
    }

    public List<Item> list() {
        Instant now = clock.instant();
        return mapper.list(LIST_LIMIT).stream().map(row -> item(row, now)).toList();
    }

    @Transactional
    public Item revoke(String actorId, String id) {
        InvitationRow row = mapper.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "邀请码不存在"));
        if (row.usedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_USED", "邀请码已使用，不能停用");
        }
        if (row.revokedAt() == null && mapper.revoke(id, clock.instant()) == 1) {
            audit.record(actorId, id, "invitation-revoked", Map.of("prefix", row.prefix()));
        }
        return item(mapper.find(id).orElseThrow(), clock.instant());
    }

    /** 检查邀请码可用（发码前、注册前），不消费。 */
    public void requireUsable(String raw) {
        if (mapper.findUsable(normalized(raw), clock.instant()).isEmpty()) throw invalid();
    }

    /** 在注册事务内消费邀请码；并发注册同一个码时只有一个成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(String raw, String userId) {
        Instant now = clock.instant();
        String id = mapper.findUsable(normalized(raw), now).orElseThrow(InvitationService::invalid);
        if (mapper.consume(id, userId, now) != 1) throw invalid();
    }

    private static byte[] normalized(String raw) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(code).matches()) throw invalid();
        return digest(code);
    }

    private static Item item(InvitationRow row, Instant now) {
        String state = row.usedAt() != null ? "used"
                : row.revokedAt() != null ? "revoked"
                : !row.expiresAt().isAfter(now) ? "expired" : "available";
        UsedBy usedBy = row.userId() == null ? null : new UsedBy(row.userId(), row.usedName(),
                row.usedEmail() != null ? row.usedEmail() : row.usedPhone());
        return new Item(row.id(), row.prefix(), state, row.createdAt(), row.expiresAt(), row.revokedAt(),
                row.usedAt(), usedBy);
    }

    private static byte[] digest(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVITATION_INVALID", "邀请码无效、已使用或已停用");
    }
}
