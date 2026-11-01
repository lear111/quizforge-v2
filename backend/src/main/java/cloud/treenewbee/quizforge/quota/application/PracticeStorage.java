package cloud.treenewbee.quizforge.quota.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import cloud.treenewbee.quizforge.quota.infrastructure.PracticeQuotaMapper;

/** Called after successful optimistic writes, before committing. Never runs for a receipt replay. */
@Service
public class PracticeStorage {
    private final PracticeQuotaMapper mapper;
    private final QuotaService quotas;
    public PracticeStorage(PracticeQuotaMapper mapper, QuotaService quotas) { this.mapper = mapper; this.quotas = quotas; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void state(String userId, String contextId, String qid) {
        // Load/lock the changed rows before acquiring the account lock; preserve context -> state -> round ordering.
        var states = mapper.states(userId, contextId, qid, mapper.collection(userId, contextId));
        for (var item : states) quotas.setStorage(userId, "state", item.objectId(), item.collectionId(), item.sizeBytes());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void context(String userId, String contextId, String qid, String roundId) {
        var states = mapper.states(userId, contextId, qid, mapper.collection(userId, contextId));
        var rounds = mapper.rounds(userId, roundId);
        var snapshots = mapper.snapshots(userId, roundId, qid);
        for (var item : states) quotas.setStorage(userId, "state", item.objectId(), item.collectionId(), item.sizeBytes());
        for (var item : rounds) quotas.setStorage(userId, "round", item.objectId(), item.collectionId(), item.sizeBytes());
        for (var item : snapshots) quotas.setStorage(userId, "snapshot", item.objectId(), item.collectionId(), item.sizeBytes());
        if (qid == null) quotas.releasePrefix(userId, "whiteboard", contextId + ":");
    }
}
