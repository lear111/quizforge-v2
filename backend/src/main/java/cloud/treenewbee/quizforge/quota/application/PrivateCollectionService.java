package cloud.treenewbee.quizforge.quota.application;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cloud.treenewbee.quizforge.quota.infrastructure.CollectionDeletionMapper;
import cloud.treenewbee.quizforge.shared.web.ApiException;

@Service
public class PrivateCollectionService {
    private final CollectionDeletionMapper mapper;
    private final QuotaService quotas;
    private final Clock clock;
    public PrivateCollectionService(CollectionDeletionMapper mapper, QuotaService quotas, Clock clock) {
        this.mapper = mapper; this.quotas = quotas; this.clock = clock;
    }
    @Transactional
    public void delete(String userId, String collectionId) {
        mapper.lock(userId, collectionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "题库不存在"));
        for (String context : mapper.contexts(userId, collectionId)) mapper.states(userId, context);
        mapper.snapshots(userId, collectionId); mapper.rounds(userId, collectionId);
        mapper.whiteboards(userId, collectionId); mapper.statesInCollection(userId, collectionId);
        mapper.exams(userId, collectionId); mapper.deleteContexts(userId, collectionId);
        mapper.markDeleted(userId, collectionId, clock.instant());
        // All business locks precede the account lock. Shared immutable content is left for reference-aware GC.
        quotas.releaseCollection(userId, collectionId);
    }
}
