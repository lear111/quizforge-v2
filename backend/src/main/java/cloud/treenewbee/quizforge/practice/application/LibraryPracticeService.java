package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.library.infrastructure.MyLibraryMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeCollectionMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeCollectionRow;
import cloud.treenewbee.quizforge.practice.management.PracticeDirectoryNames;
import cloud.treenewbee.quizforge.practice.management.PracticeDirectoryService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 已验证资源到独立练习实例：只读包/题库元数据，不读题目，不调用规则。 */
@Service
public class LibraryPracticeService {
    public record Created(String id, String title, String bankId) {}
    private final CatalogMapper catalog;
    private final MyLibraryMapper library;
    private final ImportUploadService uploads;
    private final PracticeCollectionMapper collections;
    private final PracticeDirectoryService directory;
    private final QuotaService quotas;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public LibraryPracticeService(CatalogMapper catalog, MyLibraryMapper library, ImportUploadService uploads,
            PracticeCollectionMapper collections, PracticeDirectoryService directory, QuotaService quotas,
            TransactionTemplate tx, JsonMapper json, Clock clock) {
        this.catalog = catalog;
        this.library = library;
        this.uploads = uploads;
        this.collections = collections;
        this.directory = directory;
        this.quotas = quotas;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    public List<Created> create(String user, String packId, Integer requestedRevision, List<String> requestedBanks,
            String requestedGroup, boolean ownOnly) {
        return tx.execute(status -> {
            // 包行先锁定：与资源删除、重新上传和审核互斥。
            var pack = ownOnly ? catalog.lockOwned(packId, user).orElseThrow(LibraryPracticeService::notFound)
                    : catalog.lock(packId).orElseThrow(LibraryPracticeService::notFound);
            if (!"active".equals(pack.status())) throw notFound();
            boolean publicPack = uploads.isPublic(packId, pack.ownerId(), pack.latestRevision());
            if (!publicPack && !user.equals(pack.ownerId())) throw notFound();
            int revision = requestedRevision == null
                    ? (publicPack && pack.approvedRevision() != null ? pack.approvedRevision() : pack.latestRevision())
                    : requestedRevision;
            if (publicPack && !Objects.equals(pack.approvedRevision(), revision)) throw unavailable();
            var state = uploads.reviewState(pack.ownerId(), packId, revision);
            if (!"approved".equals(state.status())) throw unavailable();
            var info = catalog.revision(packId, revision).orElseThrow(LibraryPracticeService::notFound);
            var banks = library.banks(packId, revision);
            Set<String> wanted = new LinkedHashSet<>(requestedBanks == null ? List.of() : requestedBanks);
            if (wanted.isEmpty() || wanted.size() != requestedBanks.size()
                    || !banks.stream().map(MyLibraryMapper.Bank::bankUuid).toList().containsAll(wanted)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请选择包中的题库，不可重复");
            }
            String group = PracticeDirectoryNames.group(requestedGroup);
            directory.beginImport(user, group);
            List<Created> result = new ArrayList<>();
            for (var bank : banks) {
                if (!wanted.contains(bank.bankUuid())) continue;
                String id = Ids.uuidV7();
                String rawTitle = bank.title();
                if (rawTitle.codePointCount(0, rawTitle.length()) > 200) {
                    rawTitle = rawTitle.substring(0, rawTitle.offsetByCodePoints(0, 200));
                }
                String title = directory.importTitle(user, rawTitle, group);
                collections.insert(new PracticeCollectionRow(id, user, bank.bankId(), title, group,
                        json.writeValueAsString(Map.of("type", publicPack ? "hub" : "private_pack", "packId", packId,
                                "revision", revision, "filename", info.filename())), null,
                        publicPack ? packId : null, bank.bankUuid(), revision, "current",
                        clock.instant().truncatedTo(ChronoUnit.MILLIS), null));
                if (collections.linkLibrary(id, user, packId, revision) != 1) throw new IllegalStateException("实例来源未写入");
                if (!publicPack) quotas.retainPrivateInstance(user, packId, revision);
                result.add(new Created(id, title, bank.bankId()));
            }
            return List.copyOf(result);
        });
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "题库包不存在或已删除");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "PACK_NOT_APPROVED", "只有已审核通过的题库可以导入练习空间");
    }
}
