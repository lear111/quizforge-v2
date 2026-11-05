package cloud.treenewbee.quizforge.catalog.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import cloud.treenewbee.quizforge.catalog.infrastructure.PublishedVersionsMapper;
import cloud.treenewbee.quizforge.practice.application.PublishedVersions;

/** 练习空间懒更新所需的广场版本（设计 04 第 3 节）：只看公开、未删除包的已审核版本。 */
@Component
public class CatalogPublishedVersions implements PublishedVersions {

    private final PublishedVersionsMapper mapper;

    public CatalogPublishedVersions(PublishedVersionsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, Latest> latest(Collection<String> packIds, Collection<String> uuids) {
        Map<String, Latest> result = new LinkedHashMap<>();
        if (packIds.isEmpty()) return result;
        Map<String, Map<String, String>> banks = new HashMap<>();
        if (!uuids.isEmpty()) {
            for (var row : mapper.banks(packIds, uuids)) {
                banks.computeIfAbsent(row.packId(), ignored -> new HashMap<>()).put(row.bankUuid(), row.bankId());
            }
        }
        for (var pack : mapper.packs(packIds)) {
            boolean deleted = !"active".equals(pack.status()) || !"public".equals(pack.visibility());
            result.put(pack.id(), new Latest(deleted, deleted ? null : pack.approvedRevision(),
                    Map.copyOf(banks.getOrDefault(pack.id(), Map.of()))));
        }
        return result;
    }
}
