package cloud.treenewbee.quizforge.library;

import cloud.treenewbee.quizforge.quota.application.PrivateCollectionService;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LibraryService {
    private static final Logger LOG = LoggerFactory.getLogger(LibraryService.class);
    private final LibraryMapper mapper;
    private final PrivateCollectionService deletion;
    private final TransactionTemplate transactions;

    public LibraryService(LibraryMapper mapper, PrivateCollectionService deletion, TransactionTemplate transactions) {
        this.mapper = mapper;
        this.deletion = deletion;
        this.transactions = transactions;
    }

    public record Change(String kind, String target, String action, String path, String name, String group, Long revision) {}
    public record Result(boolean deleted, int deletedCount, int remaining, boolean partial, String message) {}

    public Result manage(String user, Change change) {
        if (!"bank".equals(change.kind()) || !List.of("item", "group").contains(change.target() == null ? "" : change.target())
                || !List.of("create", "update", "delete").contains(change.action() == null ? "" : change.action())) {
            throw LibraryNames.invalid("不支持的目录操作");
        }
        if ("group".equals(change.target()) && "delete".equals(change.action())) return deleteGroup(user, change);
        return transactions.execute(status -> {
            check(user, change.revision());
            if ("item".equals(change.target())) {
                if (!"update".equals(change.action())) throw LibraryNames.invalid("不支持的题库操作");
                var item = mapper.item(user, change.path()).orElseThrow(LibraryService::notFound);
                String title = LibraryNames.title(change.name()), group = LibraryNames.group(change.group());
                if (!title.equals(item.title()) || !Objects.equals(group, item.groupName())) unique(user, item.id(), group, title);
                if (!Objects.equals(group, item.groupName()) && mapper.duplicateUuid(user, item.id(), group, item.bankId()) > 0) {
                    throw duplicateUuid("目标分组已有相同 UUID 的题库");
                }
                if (group != null) mapper.addGroup(user, group);
                changed(mapper.update(user, item.id(), title, group));
            } else {
                String name = LibraryNames.group(change.name());
                if (name == null) throw LibraryNames.invalid("请输入分组名");
                if (mapper.groups(user).contains(name) && !("update".equals(change.action()) && name.equals(change.path()))) {
                    throw new ApiException(HttpStatus.CONFLICT, "GROUP_EXISTS", "已存在同名分组");
                }
                if ("update".equals(change.action())) {
                    String old = LibraryNames.group(change.path());
                    if (old == null || !mapper.groups(user).contains(old)) throw notFound();
                    for (var item : mapper.items(user)) {
                        if (!old.equals(item.groupName())) continue;
                        changed(mapper.update(user, item.id(), item.title(), name));
                    }
                    mapper.removeGroup(user, old);
                }
                mapper.addGroup(user, name);
            }
            changed(mapper.bump(user));
            return new Result(false, 0, 0, false, "目录已更新");
        });
    }

    /** 导入与管理使用同一把用户目录锁，重名判定和插入在同一事务中。 */
    public void beginImport(String user, String group) {
        mapper.initialize(user);
        mapper.lock(user);
        if (group != null) mapper.addGroup(user, group);
        changed(mapper.bump(user));
    }

    public String importTitle(String user, String title, String group) {
        String candidate = title;
        for (int number = 2; mapper.duplicate(user, "", group, candidate) != 0; number++) {
            String suffix = "（" + number + "）";
            int keep = Math.min(title.codePointCount(0, title.length()), 200 - suffix.length());
            candidate = title.substring(0, title.offsetByCodePoints(0, keep)) + suffix;
        }
        return candidate;
    }

    public void checkImportIdentity(String user, String group, String uuid, String hash) {
        mapper.conflictingImport(user, group, uuid, java.util.HexFormat.of().parseHex(hash)).ifPresent(existing -> {
            throw duplicateUuid(existing.equalsIgnoreCase(hash) ? "此分组已存在该题库" :
                    "此分组已有相同 UUID 的其他版本，请使用版本更新流程或导入到其他分组");
        });
    }

    private static ApiException duplicateUuid(String message) {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_BANK_UUID", message);
    }

    public void deleteOne(String user, String id) {
        transactions.executeWithoutResult(status -> {
            mapper.initialize(user);
            mapper.lock(user);
            deletion.delete(user, id);
            changed(mapper.bump(user));
        });
    }

    private Result deleteGroup(String user, Change change) {
        String group = LibraryNames.group(change.path());
        if (group == null) throw LibraryNames.invalid("不能删除根目录");
        List<String> ids = transactions.execute(status -> {
            check(user, change.revision());
            if (!mapper.groups(user).contains(group)) throw notFound();
            // 先占用目录版本；之后每本删除都在独立短事务内执行。
            changed(mapper.bump(user));
            return mapper.items(user).stream().filter(item -> group.equals(item.groupName())).map(LibraryMapper.Item::id).toList();
        });
        int count = 0;
        for (String id : ids) {
            try {
                boolean removed = transactions.execute(status -> {
                    mapper.lock(user);
                    var current = mapper.item(user, id);
                    if (current.isEmpty() || !Objects.equals(current.get().groupName(), group)) return false;
                    deletion.delete(user, id);
                    changed(mapper.bump(user));
                    return true;
                });
                if (removed) count++;
            } catch (RuntimeException failure) {
                LOG.warn("删除分组时某本题库删除失败，保留剩余内容供重试", failure);
                break;
            }
        }
        int remaining = transactions.execute(status -> {
            mapper.lock(user);
            int size = (int) mapper.items(user).stream().filter(item -> group.equals(item.groupName())).count();
            if (size == 0) {
                mapper.removeGroup(user, group);
                changed(mapper.bump(user));
            }
            return size;
        });
        return new Result(true, count, remaining, remaining > 0,
                remaining > 0 ? "部分题库删除失败，剩余 " + remaining + " 本，可重新请求继续删除" : "分组及其作答、历史记录已删除");
    }

    private void check(String user, Long expected) {
        if (expected == null || expected < 0) throw LibraryNames.invalid("缺少目录版本，请刷新后重试");
        mapper.initialize(user);
        if (mapper.lock(user) != expected) throw new ApiException(HttpStatus.CONFLICT, "LIBRARY_CONFLICT", "目录已在其他页面修改，请刷新后重试");
    }

    private void unique(String user, String id, String group, String title) {
        if (mapper.duplicate(user, id, group, title) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "该分组已存在同名题库");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("目录写入未生效");
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "题库或分组不存在");
    }
}
