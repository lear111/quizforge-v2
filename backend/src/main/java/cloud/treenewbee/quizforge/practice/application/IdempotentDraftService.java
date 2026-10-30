package cloud.treenewbee.quizforge.practice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.ReceiptRow;
import cloud.treenewbee.quizforge.shared.web.ApiException;

@Service
public class IdempotentDraftService {

    private final DraftService draftService;
    private final ReceiptMapper receiptMapper;
    private final TransactionTemplate tx; // Spring 自动提供，构造器注入即可

    // 构造器自己写
    public IdempotentDraftService(DraftService draftService, ReceiptMapper receiptMapper, TransactionTemplate tx) {
        this.draftService = draftService;
        this.receiptMapper = receiptMapper;
        this.tx = tx;
    }

    public String save(String userId, String requestId, String contextId, String questionId,
            long clientRevision, String answerJson, byte[] contentSha256) {

        byte[] hash = requestHash(contextId, questionId, clientRevision, answerJson);

        try {
            // 事务 1：查回执 → 执行业务 → 写回执
            return tx.execute(status -> {
                ReceiptRow existing = receiptMapper.find(userId, requestId);
                if (existing != null) {
                    return replay(existing, hash);
                }
                long revision = draftService.saveDraft(userId, contextId, questionId, clientRevision, answerJson,
                        contentSha256); // 调用 draftService.saveDraft
                String response = "{\"revision\":" + revision + "}";
                receiptMapper.insert(userId, requestId, hash, response); // 写回执
                return response;
            });
        } catch (DuplicateKeyException e) {
            return recheck(userId, requestId, hash, e);
        } catch (ApiException e) {
            if (e.status() != HttpStatus.CONFLICT)
                throw e;
            return recheck(userId, requestId, hash, e);
        }
    }

    private String replay(ReceiptRow row, byte[] hash) {
        // 哈希相同 → 返回 row.response()
        // 哈希不同 → 抛出 422，code 用 "REQUEST_ID_REUSED"
        // 提示：比较 byte[] 不能用 ==，也不能用 equals
        if (java.util.Arrays.equals(row.requestSha256(), hash)) {
            return row.response();
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REQUEST_ID_REUSED", "请求 ID 已被重复使用");
        }
    }

    private static byte[] requestHash(String contextId, String questionId, long clientRevision, String answerJson) {
        // 我先帮你写好，这不是重点
        try {
            var md = MessageDigest.getInstance("SHA-256");
            String canonical = contextId + "\n" + questionId + "\n" + clientRevision + "\n" + answerJson;
            return md.digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String recheck(String userId, String requestId, byte[] hash, RuntimeException original) {
        // 你来写：新事务里查回执，没有 → 抛出 original，有 → replay
        return tx.execute(status -> {
            ReceiptRow existing = receiptMapper.find(userId, requestId);
            if (existing == null)
                throw original;
            return replay(existing, hash);
        });
    }
}