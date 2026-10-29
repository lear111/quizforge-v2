package cloud.treenewbee.quizforge.practice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.json.JsonMapper;

@Transactional
class PracticeCollectionMapperTest extends MySqlTestBase {
    private static final Instant NOW = Instant.parse("2026-10-29T12:34:56.789Z");
    @Autowired PracticeCollectionMapper collections;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"current", "pending", "removed"})
    void insertPersistsAllV1Columns(String updateStatus) {
        var row = new PracticeCollectionRow(Ids.uuidV7(), Ids.uuidV7(), Ids.uuidV7(), "我的题库😀", "组", "{\"type\":\"upload\"}",
                "g.legacy", Ids.uuidV7(), "question-banks/组/题库/", 3, updateStatus, NOW, NOW.plusSeconds(10));
        assertThat(collections.insert(row)).isEqualTo(1);
        var stored = jdbc.queryForMap("SELECT * FROM practice_collection WHERE id=?", row.id());
        assertThat(stored).hasSize(13);
        assertThat(stored.get("id")).isEqualTo(row.id());
        assertThat(stored.get("user_id")).isEqualTo(row.userId());
        assertThat(stored.get("bank_id")).isEqualTo(row.bankId());
        assertThat(stored.get("title")).isEqualTo(row.title());
        assertThat(stored.get("group_name")).isEqualTo(row.groupName());
        assertThat(JsonMapper.builder().build().readTree((String) stored.get("source")))
                .isEqualTo(JsonMapper.builder().build().readTree(row.source()));
        assertThat(stored.get("legacy_route_id")).isEqualTo(row.legacyRouteId());
        assertThat(stored.get("source_pack_id")).isEqualTo(row.sourcePackId());
        assertThat(stored.get("source_bank_key")).isEqualTo(row.sourceBankKey());
        assertThat(stored.get("source_revision")).isEqualTo(row.sourceRevision());
        assertThat(stored.get("update_status")).isEqualTo(row.updateStatus());
        assertThat(jdbc.<Instant>queryForObject("SELECT created_at FROM practice_collection WHERE id=?",
                (rs, index) -> rs.getTimestamp(1, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).toInstant(), row.id())).isEqualTo(NOW);
        assertThat(jdbc.<Instant>queryForObject("SELECT deleted_at FROM practice_collection WHERE id=?",
                (rs, index) -> rs.getTimestamp(1, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).toInstant(), row.id())).isEqualTo(row.deletedAt());
    }

    @Test
    void nullableColumnsAndIndependentCopiesOfSameBankAreAllowed() {
        String user = Ids.uuidV7(), bank = Ids.uuidV7();
        var first = row(Ids.uuidV7(), user, bank, null);
        var second = row(Ids.uuidV7(), user, bank, null);
        assertThat(collections.insert(first)).isEqualTo(1);
        assertThat(collections.insert(second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE user_id=? AND bank_id=?", Integer.class, user, bank)).isEqualTo(2);
        var stored = jdbc.queryForMap("SELECT * FROM practice_collection WHERE id=?", first.id());
        for (String column : new String[] {"group_name", "legacy_route_id", "source_pack_id", "source_bank_key", "source_revision", "deleted_at"}) {
            assertThat(stored.get(column)).as(column).isNull();
        }
    }

    @Test
    void primaryAndUserLegacyUniqueKeysRejectDuplicatesButDifferentUsersAreIndependent() {
        String user = Ids.uuidV7(), bank = Ids.uuidV7();
        var first = row(Ids.uuidV7(), user, bank, "g.legacy");
        collections.insert(first);
        assertThatThrownBy(() -> collections.insert(first)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> collections.insert(row(Ids.uuidV7(), user, bank, "g.legacy"))).isInstanceOf(DuplicateKeyException.class);
        assertThat(collections.insert(row(Ids.uuidV7(), Ids.uuidV7(), bank, "g.legacy"))).isEqualTo(1);
    }

    private static PracticeCollectionRow row(String id, String user, String bank, String legacy) {
        return new PracticeCollectionRow(id, user, bank, "题库", null, "{}", legacy, null, null, null, "current", NOW, null);
    }
}
