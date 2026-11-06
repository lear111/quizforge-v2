package cloud.treenewbee.quizforge.catalog.application;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.PublishedVersionsMapper;
import cloud.treenewbee.quizforge.notification.application.NotificationService;

/**
 * 题库更新通知（需求 FR-12，设计 04 第 3.3 节）：公开题库包的新版本审核通过时（含管理员上传自动通过），
 * 向拥有该包实例的用户发送一条通知，正文为作者填写的更新说明。通知只存一份，读取时按实例筛选。
 * 第 1 版没有人拥有实例，私人包不随广场更新，都不发送。
 */
@Component
public class PackUpdateNotices {

    static final int TITLE_MAX = 100;

    private final CatalogMapper catalog;
    private final PublishedVersionsMapper packs;
    private final NotificationService notifications;

    public PackUpdateNotices(CatalogMapper catalog, PublishedVersionsMapper packs, NotificationService notifications) {
        this.catalog = catalog;
        this.packs = packs;
        this.notifications = notifications;
    }

    /** 在审核通过的同一事务中调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(String packId, int revision) {
        if (revision <= 1) return;
        var pack = packs.packs(List.of(packId)).stream().findFirst().orElse(null);
        if (pack == null || !"public".equals(pack.visibility()) || !"active".equals(pack.status())) return;
        var row = catalog.revision(packId, revision).orElseThrow();
        String notes = row.releaseNotes() == null || row.releaseNotes().isBlank() ? "作者没有填写更新说明。" : row.releaseNotes();
        notifications.publishBankUpdate(packId, title(row.title(), revision),
                notes + "\n\n没有进行中的作答时，下次打开题库会自动使用新版本；有进行中的作答时，“再练一次”后使用新版本。");
    }

    static String title(String packTitle, int revision) {
        String suffix = "」已更新到第 " + revision + " 版";
        String prefix = "题库「";
        int room = TITLE_MAX - prefix.length() - suffix.length();
        String name = packTitle.codePointCount(0, packTitle.length()) <= room ? packTitle
                : packTitle.substring(0, packTitle.offsetByCodePoints(0, room - 1)) + "…";
        return prefix + name + suffix;
    }
}
