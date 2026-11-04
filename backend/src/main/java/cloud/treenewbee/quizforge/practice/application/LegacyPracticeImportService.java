package cloud.treenewbee.quizforge.practice.application;

import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.application.BankAvailability;
import cloud.treenewbee.quizforge.practice.management.PracticeDirectoryService;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeCollectionMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeCollectionRow;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 过渡兼容入口；新界面通过已审核的资源创建实例，内容模块不依赖练习目录。 */
@Service
public class LegacyPracticeImportService {
    private final ImportService imports;
    private final PracticeDirectoryService directory;
    private final PracticeCollectionMapper collections;
    private final BankAvailability availability;
    private final QuotaService quotas;
    private final JsonMapper json;

    public LegacyPracticeImportService(ImportService imports, PracticeDirectoryService directory,
            PracticeCollectionMapper collections, BankAvailability availability, QuotaService quotas, JsonMapper json) {
        this.imports = imports;
        this.directory = directory;
        this.collections = collections;
        this.availability = availability;
        this.quotas = quotas;
        this.json = json;
    }

    public record ImportWarning(String code, String message, int count, List<String> questionIds) {}
    public record ImportedCollection(String id, String bankId, String title, String groupName, boolean available,
            List<ImportWarning> warnings) {}
    /** 调用方持有上传流；解包和规则调用均在业务事务之前完成。 */
    public List<ImportedCollection> importArchive(String userId, String filename, InputStream upload) {
        Path temporary = imports.spool(upload);
        try {
            return imports.ingest(temporary, group -> directory.beginImport(userId, group),
                    ingested -> createCollections(userId, filename, ingested));
        } finally { imports.delete(temporary); }
    }

    private List<ImportedCollection> createCollections(String userId, String filename, ImportService.Ingested ingested) {
        List<ImportedCollection> result = new ArrayList<>();
        for (ImportService.StoredBank bank : ingested.banks()) {
            String id = Ids.uuidV7(), title = directory.importTitle(userId, bank.title(), ingested.group());
            collections.insert(new PracticeCollectionRow(id, userId, bank.bankId(), title, ingested.group(),
                    json.writeValueAsString(Map.of("type", "upload", "filename", filename)), null, null, bank.bankUuid(), null, "current",
                    ingested.now(), null));
            quotas.setStorage(userId, "import", id, id, bank.logicalBytes());
            List<ImportWarning> warnings = bank.omittedIds().isEmpty() ? List.of() : List.of(new ImportWarning(
                    "QUESTIONS_NOT_IN_OUTLINE", bank.omittedIds().size() + " 道题未出现在大纲中，不会显示",
                    bank.omittedIds().size(), bank.omittedIds()));
            result.add(new ImportedCollection(id, bank.bankId(), title, ingested.group(),
                    availability.isAvailable(bank.bankId()), warnings));
        }
        return List.copyOf(result);
    }

}
