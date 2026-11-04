package cloud.treenewbee.quizforge.library.application;

import java.util.List;
import org.springframework.stereotype.Service;
import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import cloud.treenewbee.quizforge.library.infrastructure.MyLibraryMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.springframework.http.HttpStatus;

/** 我的私人包与公开发布共用目录；详情保留每次提交的结果。 */
@Service
public class MyLibraryService {
    public record Entry(String id, String visibility, int revision, String title, String status, String reason,
            boolean originalAvailable) {}
    public record Version(int revision, String filename, long size, String status, String reason,
            boolean originalAvailable, java.time.Instant submittedAt, List<MyLibraryMapper.Bank> banks) {}
    public record Detail(String id, List<Version> revisions) {}
    private final MyLibraryMapper library;
    private final CatalogMapper catalog;
    private final ImportUploadService uploads;

    public MyLibraryService(MyLibraryMapper library, CatalogMapper catalog, ImportUploadService uploads) {
        this.library = library;
        this.catalog = catalog;
        this.uploads = uploads;
    }

    public List<Entry> mine(String user, String after) {
        return library.mine(user, after).stream().map(pack -> {
            var original = library.original(user, pack.id(), pack.latestRevision()).orElseThrow(MyLibraryService::notFound);
            var state = uploads.reviewState(user, pack.id(), pack.latestRevision());
            return new Entry(pack.id(), pack.visibility(), pack.latestRevision(), pack.title(), state.status(),
                    state.reason(), original.blobSha256() != null);
        }).toList();
    }

    public Detail detail(String user, String pack) {
        // SQL 归属检查先于版本与题库读取。
        latestOwnedRevision(user, pack);
        return new Detail(pack, catalog.revisions(pack).stream().map(row -> {
            var state = uploads.reviewState(user, pack, row.revision());
            return new Version(row.revision(), row.filename(), row.size(), state.status(), state.reason(),
                    row.blobSha256() != null, row.submittedAt(), library.banks(pack, row.revision()));
        }).toList());
    }

    private int latestOwnedRevision(String user, String pack) {
        // 此查询同时限定所有者与未删除状态，避免通过任意包 ID 列出别人的版本。
        return catalog.ownedActive(pack, user).orElseThrow(MyLibraryService::notFound).latestRevision();
    }

    public MyLibraryMapper.Original original(String user, String pack, int revision) {
        var file = library.original(user, pack, revision).orElseThrow(MyLibraryService::notFound);
        if (file.blobSha256() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ORIGINAL_UNAVAILABLE", "原文件不可用");
        }
        return file;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "题库包不存在或已删除");
    }
}
