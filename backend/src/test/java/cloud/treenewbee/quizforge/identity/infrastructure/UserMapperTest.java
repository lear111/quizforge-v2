package cloud.treenewbee.quizforge.identity.infrastructure;

import static cloud.treenewbee.quizforge.identity.infrastructure.MapperFixtures.NOW;
import static cloud.treenewbee.quizforge.identity.infrastructure.MapperFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.shared.id.Ids;

@Transactional
@ResourceLock("java.util.TimeZone.default")
class UserMapperTest extends MySqlTestBase {
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertAndFindByIdRoundTripEveryColumn() {
        UserRow expected = user();
        assertThat(users.insert(expected)).isEqualTo(1);
        assertThat(users.findById(expected.id())).contains(expected);
    }

    @Test
    void insertAllowsNullEmailAndNonAdmin() {
        UserRow expected = new UserRow(Ids.uuidV7(), null, "无邮箱用户", "hash", "scrypt-v1",
                false, NOW, NOW, 0);
        assertThat(users.insert(expected)).isEqualTo(1);
        assertThat(users.findById(expected.id())).contains(expected);
    }

    @Test
    void insertRejectsDuplicateIdAndEmail() {
        UserRow existing = user();
        users.insert(existing);
        assertThatThrownBy(() -> users.insert(existing)).isInstanceOf(DuplicateKeyException.class);
        UserRow duplicateEmail = new UserRow(Ids.uuidV7(), existing.email(), "other", "hash", "scrypt-v1",
                false, NOW, NOW, 0);
        assertThatThrownBy(() -> users.insert(duplicateEmail)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByIdReturnsEmptyForMissingOrSqlInput() {
        users.insert(user());
        assertThat(users.findById(Ids.uuidV7())).isEmpty();
        assertThat(users.findById("' OR 1=1 -- ")).isEmpty();
    }

    @Test
    void findByEmailMatchesExactBytesWithoutNormalizing() {
        UserRow expected = user();
        users.insert(expected);
        assertThat(users.findByEmail(expected.email())).contains(expected);
        assertThat(users.findByEmail(expected.email().toUpperCase(java.util.Locale.ROOT))).isEmpty();
        assertThat(users.findByEmail(expected.email().replace("example", "éxample"))).isEmpty();
        assertThat(users.findByEmail(expected.email() + " ")).isEmpty();
        assertThat(users.findByEmail("missing@example.test")).isEmpty();
        assertThat(users.findByEmail("' OR 1=1 -- ")).isEmpty();
        assertThat(users.findByEmail(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai", "America/Los_Angeles"})
    void instantsRoundTripAsUtcAcrossDefaultTimeZones(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            UserRow expected = user();
            users.insert(expected);
            // Check actual UTC wall time too: a symmetric timezone conversion could hide a bug.
            assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f') "
                    + "FROM account_user WHERE id = ?", String.class, expected.id()))
                    .isEqualTo("2026-10-27 12:34:56.789000");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertThat(users.findById(expected.id())).contains(expected);
            assertThat(users.findByEmail(expected.email())).contains(expected);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
