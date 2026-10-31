package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.admin.infrastructure.ExtensionReviewMapper;
import cloud.treenewbee.quizforge.admin.infrastructure.ExtensionReviewRow;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.json.JsonMapper;

@Transactional
@ResourceLock("java.util.TimeZone.default")
class ContentMapperTest extends MySqlTestBase {
    static final Instant NOW = Instant.parse("2026-10-28T12:34:56.789Z");
    @Autowired BlobMapper blobs;
    @Autowired ExtensionPackageMapper packages;
    @Autowired ExtensionFileMapper files;
    @Autowired BankMapper banks;
    @Autowired QuestionMapper questions;
    @Autowired BankAssetMapper assets;
    @Autowired ExtensionReviewMapper reviews;
    @Autowired JdbcTemplate jdbc;

    static byte[] hash() throws Exception { return MessageDigest.getInstance("SHA-256").digest(Ids.uuidV7().getBytes(StandardCharsets.UTF_8)); }

    private BankRow bank(byte[] digest) {
        return new BankRow(Ids.uuidV7(), digest, "中文题库", "说明😀", 1, "{\"exam\":true}", "{\"tracks\":[]}", 2, NOW);
    }

    @Test
    void blobInsertIfAbsentPreservesFirstMetadataAndFindsBinaryPrimaryKey() throws Exception {
        byte[] hash = hash();
        assertThat(blobs.insertIfAbsent(new BlobRow(hash, 10, "image/png", NOW))).isEqualTo(1);
        assertThat(blobs.insertIfAbsent(new BlobRow(hash, 20, "text/plain", NOW.plusSeconds(5)))).isZero();
        var stored = blobs.findBySha256(hash).orElseThrow();
        assertThat(stored.sha256()).containsExactly(hash); assertThat(stored.size()).isEqualTo(10);
        assertThat(stored.mime()).isEqualTo("image/png"); assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(blobs.findBySha256(hash())).isEmpty();
    }

    @Test
    void extensionPackageRoundTripsEveryColumnAndRejectsDuplicateDigest() throws Exception {
        byte[] hash = hash();
        ExtensionPackageRow row = new ExtensionPackageRow(hash, "qf.基础", "1.2.3", "拓展😀", "{\"name\":\"测试\",\"v\":1}", 1, 5, NOW);
        assertThat(packages.insert(row)).isEqualTo(1);
        var stored = packages.findByDigest(hash).orElseThrow();
        assertThat(stored).usingRecursiveComparison().ignoringFields("manifest").isEqualTo(row);
        assertJson(stored.manifest(), row.manifest());
        assertThat(packages.findByDigest(hash())).isEmpty();
        assertThatThrownBy(() -> packages.insert(row)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void extensionFileCompositeLookupUsesExactPathAndPackage() throws Exception {
        byte[] digest = hash(), blob = hash();
        var row = new ExtensionFileRow(digest, "examples/中文.json", blob);
        assertThat(files.insert(row)).isEqualTo(1);
        assertThat(files.findByPath(digest, row.path()).orElseThrow()).usingRecursiveComparison().isEqualTo(row);
        assertThat(files.findByPath(hash(), row.path())).isEmpty();
        assertThat(files.findByPath(digest, "examples/中文.json ")).isEmpty();
        assertThat(files.findByPath(digest, "EXAMPLES/中文.json")).isEmpty();
        assertThat(files.findByPath(digest, "' OR 1=1 -- ")).isEmpty();
        assertThatThrownBy(() -> files.insert(row)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void bankPrimaryAndContentUniqueKeysRoundTripJsonAndNullableFields() throws Exception {
        byte[] digest = hash();
        BankRow row = bank(digest);
        assertThat(banks.insert(row)).isEqualTo(1);
        for (BankRow stored : new BankRow[] {banks.findById(row.id()).orElseThrow(), banks.findByContentSha256(digest).orElseThrow()}) {
            assertThat(stored).usingRecursiveComparison().ignoringFields("features", "listening").isEqualTo(row);
            assertJson(stored.features(), row.features()); assertJson(stored.listening(), row.listening());
        }
        assertThatThrownBy(() -> banks.insert(bank(digest))).isInstanceOf(DuplicateKeyException.class);
        assertThat(banks.findById(Ids.uuidV7())).isEmpty(); assertThat(banks.findByContentSha256(hash())).isEmpty();
        var nullable = new BankRow(Ids.uuidV7(), hash(), "上传题库", null, 1, "{}", null, 0, NOW);
        banks.insert(nullable);
        assertThat(banks.findById(nullable.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(nullable);
    }

    @Test
    void questionPrimaryAndPositionUniqueKeysPreserveDecimalJsonAndNulls() throws Exception {
        String bank = Ids.uuidV7();
        var row = new QuestionRow(bank, "Q-1", 0, "原始题目", hash(), "{\"label\":\"一\"}", null,
                "{\"answer\":\"私有答案\",\"score\":1.25}", new BigDecimal("1234.5678"), hash(), 9999, "分组😀");
        assertThat(questions.insert(row)).isEqualTo(1);
        for (var stored : new QuestionRow[] {questions.findById(bank, "Q-1").orElseThrow(), questions.findByPosition(bank, 0).orElseThrow()}) {
            assertThat(stored).usingRecursiveComparison().ignoringFields("data", "outline").isEqualTo(row);
            assertJson(stored.data(), row.data()); assertJson(stored.outline(), row.outline());
        }
        assertThat(questions.findById(bank, "q-1")).isEmpty();
        assertThat(questions.findById(Ids.uuidV7(), "Q-1")).isEmpty(); assertThat(questions.findByPosition(bank, 1)).isEmpty();
        assertThatThrownBy(() -> questions.insert(row)).isInstanceOf(DuplicateKeyException.class);
        var samePosition = new QuestionRow(bank, "Q-2", 0, "second", hash(), null, null, "{}", BigDecimal.ZERO, hash(), null, null);
        assertThatThrownBy(() -> questions.insert(samePosition)).isInstanceOf(DuplicateKeyException.class);
        var nullable = new QuestionRow(bank, "Q-2", 1, "second", hash(), null, "{}", "[]", new BigDecimal("0.0000"), hash(), null, null);
        questions.insert(nullable);
        assertThat(questions.findById(bank, "Q-2").orElseThrow()).usingRecursiveComparison().isEqualTo(nullable);
    }

    @Test
    void bankAssetCompositeKeyDoesNotReturnAnotherBankOrNonExactPath() throws Exception {
        String bank = Ids.uuidV7();
        var row = new BankAssetRow(bank, "media/Audio.mp3", hash());
        assertThat(assets.insert(row)).isEqualTo(1);
        assertThat(assets.findByPath(bank, row.path()).orElseThrow()).usingRecursiveComparison().isEqualTo(row);
        assertThat(assets.findByPath(Ids.uuidV7(), row.path())).isEmpty();
        assertThat(assets.findByPath(bank, "media/audio.mp3")).isEmpty();
        assertThat(assets.findByPath(bank, "media/Audio.mp3 ")).isEmpty();
        assertThatThrownBy(() -> assets.insert(row)).isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "revoked", "pending", "rejected"})
    void reviewsPersistAllFourStatusesAndNullablePendingMetadata(String status) throws Exception {
        boolean pending = status.equals("pending");
        var row = new ExtensionReviewRow(hash(), "qf.type", "1.0.0", status, pending ? null : "builtin-release",
                pending ? null : NOW, pending ? null : "原因😀");
        assertThat(reviews.insert(row)).isEqualTo(1);
        assertThat(reviews.findByDigest(row.digest()).orElseThrow()).usingRecursiveComparison().isEqualTo(row);
        assertThat(reviews.findByDigest(hash())).isEmpty();
        assertThatThrownBy(() -> reviews.insert(row)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void reviewRejectsUnknownStatus() throws Exception {
        var row = new ExtensionReviewRow(hash(), "type", "1", "unknown", null, null, null);
        assertThatThrownBy(() -> reviews.insert(row)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void caseAndUnicodeDistinctPathsAndQuestionIdsCanCoexist() throws Exception {
        byte[] digest = hash(); String bank = Ids.uuidV7();
        for (String path : new String[] {"A.js", "a.js", "é.js", "e\u0301.js", "a.js "}) {
            files.insert(new ExtensionFileRow(digest, path, hash()));
            assets.insert(new BankAssetRow(bank, path, hash()));
            assertThat(files.findByPath(digest, path).orElseThrow().path()).isEqualTo(path);
            assertThat(assets.findByPath(bank, path).orElseThrow().path()).isEqualTo(path);
        }
        int position = 0;
        for (String id : new String[] {"Q", "q"}) {
            questions.insert(new QuestionRow(bank, id, position++, "题目", digest, null, null, "{}", new BigDecimal("1.0000"), hash(), null, null));
            assertThat(questions.findById(bank, id).orElseThrow().questionId()).isEqualTo(id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai", "America/Los_Angeles"})
    void contentTimestampsRemainMillisecondUtcAcrossLocalTimezones(String zone) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var row = bank(hash()); banks.insert(row);
            assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM content_bank WHERE id=?", String.class, row.id()))
                    .isEqualTo("2026-10-28 12:34:56.789000");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertThat(banks.findById(row.id()).orElseThrow().createdAt()).isEqualTo(NOW);
        } finally { TimeZone.setDefault(original); }
    }

    private static void assertJson(String actual, String expected) {
        var json = JsonMapper.builder().build();
        assertThat(json.readTree(actual)).isEqualTo(json.readTree(expected));
    }
}
