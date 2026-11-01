package cloud.treenewbee.quizforge.practice.whiteboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.practice.application.PracticeReadService;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WhiteboardService {
    private final PracticeReadService reads;
    private final PracticeReadMapper readMapper;
    private final PracticeContextMapper contexts;
    private final WhiteboardMapper mapper;
    private final WhiteboardDocuments documents;
    private final WhiteboardViews views;
    private final ReceiptMapper receipts;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;

    public WhiteboardService(PracticeReadService reads, PracticeReadMapper readMapper, PracticeContextMapper contexts,
            WhiteboardMapper mapper, WhiteboardDocuments documents, WhiteboardViews views, ReceiptMapper receipts,
            TransactionTemplate tx, Clock clock, JsonMapper json) {
        this.reads=reads;this.readMapper=readMapper;this.contexts=contexts;this.mapper=mapper;this.documents=documents;
        this.views=views;this.receipts=receipts;this.tx=tx;this.clock=clock;this.json=json;
    }
    public JsonNode save(String userId,String collectionId,String qid,String requestId,long revision,JsonNode data,String contentVersion) {
        if (data==null || !data.isObject() || !data.has("draft")) throw invalid();
        String layer=data.path("layer").asString("draft");
        if (!java.util.Set.of("draft","notes").contains(layer)) throw invalid();
        var next=documents.normalize(data.get("draft"));
        var target=reads.resolveWhiteboard(userId,collectionId,qid,contentVersion);
        byte[] hash=hash(collectionId,qid,revision,data,contentVersion);
        try {
            return tx.execute(status -> {
                // Same order as restart/deletion. No context, answer or round mutation is performed.
                var context=contexts.lockForUpdate(target.contextId(),userId).orElseThrow(cloud.treenewbee.quizforge.content.application.ContentPages::notFound);
                var old=mapper.lock(target.contextId(),qid,userId).orElse(null);
                var replay=receipt(userId,requestId,hash);
                if(replay!=null)return replay;
                if ("archiving".equals(context.completionStatus())) throw new ApiException(HttpStatus.CONFLICT,"PRACTICE_BUSY","正在保存历史，请稍后重试");
                if ((old==null?0:old.revision())!=revision) throw conflict();
                String roundStatus=readMapper.roundStatus(target.contextId(),userId).orElse(null);
                var before=views.view(old,roundStatus);
                if(layer.equals("draft"))WhiteboardDocuments.requireDraftWritable(roundStatus);
                else documents.requireAppendOnly(before.notes(),next);
                var draft=layer.equals("draft")?next:before.draft();
                var notes=layer.equals("notes")?next:before.notes();
                int size=Math.addExact(documents.bytes(draft),documents.bytes(notes));
                if(size>WhiteboardDocuments.MAX_BYTES)throw WhiteboardDocuments.tooLarge();
                var row=new WhiteboardMapper.Row(target.contextId(),qid,userId,json.writeValueAsString(draft),
                        json.writeValueAsString(notes),size,old==null?1:revision,clock.instant());
                if(old==null)mapper.insert(row);else if(mapper.update(row)!=1)throw conflict();
                var response=json.createObjectNode();
                response.set("whiteboard",json.valueToTree(new WhiteboardViews.View(draft,notes,revision+1,"completed".equals(roundStatus))));
                response.set("draft",draft);
                receipts.insert(userId,requestId,hash,json.writeValueAsString(response));
                return receipt(userId,requestId,hash); // Return the same MySQL JSON normalization on first call and replay.
            });
        } catch(DuplicateKeyException error) { return recheck(userId,requestId,hash,error); }
        catch(ApiException error) {
            if(error.status()!=HttpStatus.CONFLICT)throw error;
            return recheck(userId,requestId,hash,error);
        }
    }
    private JsonNode recheck(String user,String request,byte[] hash,RuntimeException original) {
        return tx.execute(status->{var replay=receipt(user,request,hash);if(replay==null)throw original;return replay;});
    }
    private JsonNode receipt(String user,String request,byte[] hash) {
        var row=receipts.find(user,request);if(row==null)return null;
        if(!Arrays.equals(row.requestSha256(),hash))throw new ApiException(HttpStatus.BAD_REQUEST,"REQUEST_ID_REUSED","请求 ID 已被重复使用");
        return json.readTree(row.response());
    }
    private byte[] hash(String collection,String qid,long revision,JsonNode data,String version) {
        try { return MessageDigest.getInstance("SHA-256").digest(("whiteboard\n"+collection+"\n"+qid+"\n"+revision+"\n"+version+"\n"+json.writeValueAsString(data)).getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","白板操作需要有效的 data.draft 与 layer");}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"REVISION_CONFLICT","白板已在别处修改，请刷新后重试");}
}
