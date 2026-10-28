package cloud.treenewbee.quizforge.identity.infrastructure;

import static cloud.treenewbee.quizforge.identity.infrastructure.MapperFixtures.NOW;
import static cloud.treenewbee.quizforge.identity.infrastructure.MapperFixtures.session;
import static cloud.treenewbee.quizforge.identity.infrastructure.MapperFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.MySqlTestBase;

@Transactional
@ResourceLock("java.util.TimeZone.default")
class SessionMapperTest extends MySqlTestBase {
    @Autowired UserMapper users;
    @Autowired SessionMapper sessions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertAndFindActiveRoundTripEveryColumn() {
        SessionRow expected = newSession();
        assertThat(sessions.insert(expected)).isEqualTo(1);
        assertSession(expected);
        assertThatThrownBy(() -> sessions.insert(expected)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void insertAllowsNullUserAgent() {
        SessionRow base = newSession();
        SessionRow expected = new SessionRow(base.tokenHash(), base.userId(), base.createdAt(),
                base.lastUsedAt(), base.idleExpiresAt(), base.absoluteExpiresAt(), null);
        assertThat(sessions.insert(expected)).isEqualTo(1);
        assertSession(expected);
    }

    @Test
    void findActiveReturnsEmptyForUnknownHash() {
        sessions.insert(newSession());
        assertThat(sessions.findActive(newSession().tokenHash(), NOW)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0, 60", "-1, 60", "60, 0", "60, -1"})
    void findActiveRejectsIdleOrAbsoluteExpiryIncludingBoundary(long idleMillis, long absoluteMillis) {
        SessionRow expected = withExpiry(newSession(), NOW.plusMillis(idleMillis), NOW.plusMillis(absoluteMillis));
        sessions.insert(expected);
        assertThat(sessions.findActive(expected.tokenHash(), NOW)).isEmpty();
    }

    @Test
    void findActiveAcceptsOneMillisecondBeforeBothExpiries() {
        SessionRow expected = withExpiry(newSession(), NOW.plusMillis(1), NOW.plusMillis(1));
        sessions.insert(expected);
        assertSession(expected);
    }

    @Test
    void findActiveRejectsBlockedUserButNotAnotherUser() {
        SessionRow blocked = newSession();
        SessionRow allowed = newSession();
        sessions.insert(blocked);
        sessions.insert(allowed);
        jdbc.update("""
                INSERT INTO account_block (user_id, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?)
                """, blocked.userId(), allowed.userId(), "test block", java.time.LocalDateTime.ofInstant(NOW,
                        java.time.ZoneOffset.UTC));
        assertThat(sessions.findActive(blocked.tokenHash(), NOW)).isEmpty();
        assertSession(allowed);
    }

    @Test
    void touchUpdatesOnlyRequestedSessionAndUsageColumns() {
        SessionRow target = newSession();
        SessionRow other = newSession();
        sessions.insert(target);
        sessions.insert(other);
        Instant lastUsed = NOW.plusMillis(123);
        Instant idleExpiry = NOW.plusSeconds(120);
        assertThat(sessions.touch(target.tokenHash(), lastUsed, idleExpiry)).isEqualTo(1);
        assertSession(new SessionRow(target.tokenHash(), target.userId(), target.createdAt(), lastUsed,
                idleExpiry, target.absoluteExpiresAt(), target.userAgent()));
        assertSession(other);
        assertThat(sessions.touch(newSession().tokenHash(), lastUsed, idleExpiry)).isZero();
    }

    @Test
    void deleteByHashDeletesOnlyRequestedSessionAndReturnsCount() {
        SessionRow target = newSession();
        SessionRow sameUser = session(target.userId());
        sessions.insert(target);
        sessions.insert(sameUser);
        assertThat(sessions.deleteByHash(target.tokenHash())).isEqualTo(1);
        assertThat(sessions.findActive(target.tokenHash(), NOW)).isEmpty();
        assertSession(sameUser);
        assertThat(sessions.deleteByHash(target.tokenHash())).isZero();
    }

    @Test
    void deleteByUserIdDeletesOnlyThatUsersSessions() {
        SessionRow first = newSession();
        SessionRow second = session(first.userId());
        SessionRow other = newSession();
        sessions.insert(first);
        sessions.insert(second);
        sessions.insert(other);
        assertThat(sessions.deleteByUserId(first.userId())).isEqualTo(2);
        assertThat(sessions.findActive(first.tokenHash(), NOW)).isEmpty();
        assertThat(sessions.findActive(second.tokenHash(), NOW)).isEmpty();
        assertSession(other);
        assertThat(sessions.deleteByUserId(first.userId())).isZero();
    }

    @Test
    void deleteExpiredDeletesEitherExpiryIncludingBoundaryAndReturnsCount() {
        SessionRow idleBoundary = withExpiry(newSession(), NOW, NOW.plusSeconds(60));
        SessionRow absoluteBoundary = withExpiry(newSession(), NOW.plusSeconds(60), NOW);
        SessionRow bothExpired = withExpiry(newSession(), NOW.minusMillis(1), NOW.minusMillis(1));
        SessionRow active = withExpiry(newSession(), NOW.plusMillis(1), NOW.plusMillis(1));
        for (SessionRow row : new SessionRow[] {idleBoundary, absoluteBoundary, bothExpired, active}) {
            sessions.insert(row);
        }
        assertThat(sessions.deleteExpired(NOW)).isEqualTo(3);
        for (SessionRow expired : new SessionRow[] {idleBoundary, absoluteBoundary, bothExpired}) {
            // Query before creation so findActive cannot mask a failure to physically delete.
            assertThat(sessions.findActive(expired.tokenHash(), NOW.minusSeconds(120))).isEmpty();
        }
        assertSession(active);
        assertThat(sessions.deleteExpired(NOW)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai", "America/Los_Angeles"})
    void instantsAndTouchRoundTripAsUtcAcrossDefaultTimeZones(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            SessionRow expected = newSession();
            sessions.insert(expected);
            assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(last_used_at, '%Y-%m-%d %H:%i:%s.%f') "
                    + "FROM auth_session WHERE token_hash = ?", String.class, expected.tokenHash()))
                    .isEqualTo("2026-10-27 12:34:55.789000");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertSession(expected);
            Instant used = NOW.plusMillis(123);
            Instant idle = NOW.plusSeconds(120);
            assertThat(sessions.touch(expected.tokenHash(), used, idle)).isEqualTo(1);
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            assertSession(new SessionRow(expected.tokenHash(), expected.userId(), expected.createdAt(), used,
                    idle, expected.absoluteExpiresAt(), expected.userAgent()));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private SessionRow newSession() {
        UserRow owner = user();
        users.insert(owner);
        return session(owner.id());
    }

    private void assertSession(SessionRow expected) {
        assertThat(sessions.findActive(expected.tokenHash(), NOW)).hasValueSatisfying(actual ->
                assertThat(actual).usingRecursiveComparison().isEqualTo(expected));
    }

    private static SessionRow withExpiry(SessionRow row, Instant idle, Instant absolute) {
        return new SessionRow(row.tokenHash(), row.userId(), row.createdAt(), row.lastUsedAt(), idle,
                absolute, row.userAgent());
    }
}
