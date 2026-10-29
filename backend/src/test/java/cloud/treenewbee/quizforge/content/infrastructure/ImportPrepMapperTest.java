package cloud.treenewbee.quizforge.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cloud.treenewbee.quizforge.content.infrastructure.ContentMapperTest.hash;
import static cloud.treenewbee.quizforge.content.infrastructure.ContentMapperTest.NOW;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.json.JsonMapper;

@Transactional
class ImportPrepMapperTest extends MySqlTestBase {
    @Autowired ExtensionPackageMapper packages;
    @Autowired ExtensionFileMapper files;
    @Autowired BlobMapper blobs;
    @Autowired BankMapper banks;
    @Autowired QuestionMapper questions;
    @Autowired BankAssetMapper assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void packageInsertIfAbsentPreservesExistingMetadataAtExpandedLimits() throws Exception {
        byte[] digest = hash();
        var row = new ExtensionPackageRow(digest, "type", "v".repeat(120), "名".repeat(300), "{}", 1, 5, NOW);
        assertThat(packages.insertIfAbsent(row)).isEqualTo(1);
        assertThat(packages.insertIfAbsent(new ExtensionPackageRow(digest, "other", "2", "changed", "{}", 2, 0, NOW.plusSeconds(1)))).isZero();
        assertThat(packages.findByDigest(digest).orElseThrow()).usingRecursiveComparison().isEqualTo(row);
    }

    @Test
    void fileInsertIfAbsentPreservesBlobOnCompositeKeyConflict() throws Exception {
        byte[] digest = hash(), blob = hash();
        var row = new ExtensionFileRow(digest, "rules.js", blob);
        assertThat(files.insertIfAbsent(row)).isEqualTo(1);
        assertThat(files.insertIfAbsent(new ExtensionFileRow(digest, "rules.js", hash()))).isZero();
        assertThat(files.findByPath(digest, "rules.js").orElseThrow()).usingRecursiveComparison().isEqualTo(row);
        assertThat(files.insertIfAbsent(new ExtensionFileRow(digest, "Rules.js", hash()))).isEqualTo(1);
        assertThat(files.insertIfAbsent(new ExtensionFileRow(hash(), "rules.js", hash()))).isEqualTo(1);
    }

    @Test
    void blobInsertIfAbsentStillPreservesMetadataOnPrimaryKeyConflict() throws Exception {
        byte[] digest = hash();
        var row = new BlobRow(digest, 100, "image/png", NOW);
        assertThat(blobs.insertIfAbsent(row)).isEqualTo(1);
        assertThat(blobs.insertIfAbsent(new BlobRow(digest, 200, "audio/mpeg", NOW.plusSeconds(1)))).isZero();
        assertThat(blobs.findBySha256(digest).orElseThrow()).usingRecursiveComparison().isEqualTo(row);
    }

    @Test
    void bankInsertRejectsDuplicateContentAndShareLookupFindsOriginalId() throws Exception {
        byte[] digest = hash();
        var first = bank(digest);
        assertThat(banks.insert(first)).isEqualTo(1);
        assertThatThrownBy(() -> banks.insert(bank(digest))).isInstanceOf(DuplicateKeyException.class);
        assertThat(banks.findIdByContentSha256ForShare(digest)).contains(first.id());
        assertThat(banks.findIdByContentSha256ForShare(hash())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shareLookupSeesConcurrentWinnerAfterDuplicateDespiteRepeatableReadSnapshot() throws Exception {
        byte[] digest = hash();
        BankRow winner = bank(digest), loser = bank(digest);
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                assertThat(banks.findByContentSha256(digest)).isEmpty(); // establish the old snapshot
                try {
                    executor.submit(() -> new TransactionTemplate(transactions).execute(s -> banks.insert(winner)))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(banks.findByContentSha256(digest)).isEmpty();
                assertThatThrownBy(() -> banks.insert(loser)).isInstanceOf(DuplicateKeyException.class);
                assertThat(banks.findIdByContentSha256ForShare(digest)).contains(winner.id());
            });
        } finally {
            jdbc.update("DELETE FROM content_bank WHERE id IN (?, ?)", winner.id(), loser.id());
        }
    }

    @Test
    void questionBatchPersistsEveryColumnAndOriginalOrderingInOneStatement() throws Exception {
        String bank = Ids.uuidV7();
        var first = question(bank, "q1", 0);
        var second = new QuestionRow(bank, "q2", 1, "第二题", hash(), "[]", "{\"track\":\"a\"}", "null", new BigDecimal("0.0000"), hash());
        assertThat(questions.insertBatch(List.of(first, second))).isEqualTo(2);
        assertThat(questions.findById(bank, "q1").orElseThrow()).usingRecursiveComparison().ignoringFields("data").isEqualTo(first);
        assertThat(JsonMapper.builder().build().readTree(questions.findByPosition(bank, 0).orElseThrow().data()))
                .isEqualTo(JsonMapper.builder().build().readTree(first.data()));
        assertThat(questions.findByPosition(bank, 1).orElseThrow()).usingRecursiveComparison().ignoringFields("listening").isEqualTo(second);
        assertThat(JsonMapper.builder().build().readTree(questions.findById(bank, "q2").orElseThrow().listening()))
                .isEqualTo(JsonMapper.builder().build().readTree(second.listening()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"primary", "position"})
    void questionBatchUniqueConflictsRollBackEntireStatement(String conflict) throws Exception {
        String bank = Ids.uuidV7();
        var existing = question(bank, "existing", 0);
        questions.insert(existing);
        var fresh = question(bank, "fresh", 1);
        var duplicate = question(bank, conflict.equals("primary") ? "existing" : "other", conflict.equals("primary") ? 2 : 0);
        assertThatThrownBy(() -> questions.insertBatch(List.of(fresh, duplicate))).isInstanceOf(DuplicateKeyException.class);
        assertThat(questions.findById(bank, "fresh")).isEmpty();
        assertThat(questions.findById(bank, "existing").orElseThrow().title()).isEqualTo(existing.title());
    }

    @Test
    void assetBatchPersistsBinaryHashesAndCaseSensitivePaths() throws Exception {
        String bank = Ids.uuidV7();
        var first = new BankAssetRow(bank, "assets/A.png", hash());
        var second = new BankAssetRow(bank, "assets/a.png", hash());
        assertThat(assets.insertBatch(List.of(first, second))).isEqualTo(2);
        assertThat(assets.findByPath(bank, first.path()).orElseThrow()).usingRecursiveComparison().isEqualTo(first);
        assertThat(assets.findByPath(bank, second.path()).orElseThrow()).usingRecursiveComparison().isEqualTo(second);
    }

    @Test
    void assetBatchDuplicateKeyRollsBackWholeStatementWithoutOverwriting() throws Exception {
        String bank = Ids.uuidV7();
        var original = new BankAssetRow(bank, "assets/original.png", hash());
        assets.insert(original);
        var fresh = new BankAssetRow(bank, "assets/fresh.png", hash());
        assertThatThrownBy(() -> assets.insertBatch(List.of(fresh, new BankAssetRow(bank, original.path(), hash()))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(assets.findByPath(bank, fresh.path())).isEmpty();
        assertThat(assets.findByPath(bank, original.path()).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
    }

    private static BankRow bank(byte[] digest) {
        return new BankRow(Ids.uuidV7(), digest, "题库", null, 2, "{}", null, 1, NOW);
    }

    private static QuestionRow question(String bank, String id, int position) throws Exception {
        return new QuestionRow(bank, id, position, "题目中文", hash(), null, null,
                "{\"answer\":\"原始答案\",\"weight\":1.25}", new BigDecimal("12.3456"), hash());
    }
}
