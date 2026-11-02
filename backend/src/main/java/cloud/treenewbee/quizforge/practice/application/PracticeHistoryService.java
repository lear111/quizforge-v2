package cloud.treenewbee.quizforge.practice.application;

import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.practice.infrastructure.*;
import cloud.treenewbee.quizforge.practice.whiteboard.WhiteboardDocuments;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 历史仅依赖冻结状态与不可变拓展摘要，不读取当前题目原文。 */
@Service
public class PracticeHistoryService {
    private final PracticeHistoryMapper mapper;
    private final PracticeReadMapper reads;
    private final ContentPages pages;
    private final ReceiptMapper receipts;
    private final WhiteboardDocuments boards;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    public PracticeHistoryService(PracticeHistoryMapper mapper,PracticeReadMapper reads,ContentPages pages,
            ReceiptMapper receipts,WhiteboardDocuments boards,TransactionTemplate tx,JsonMapper json,Clock clock) {
        this.mapper=mapper;this.reads=reads;this.pages=pages;this.receipts=receipts;this.boards=boards;this.tx=tx;this.json=json;this.clock=clock;
    }
    private void owned(String user,String collection) { reads.collection(user,collection).orElseThrow(ContentPages::notFound); }
    public JsonNode list(String user,String collection) {
        owned(user,collection);
        var response=json.createObjectNode();response.set("records",json.valueToTree(mapper.list(user,collection)));
        return response;
    }
    public JsonNode detail(String user,String collection,String id) throws java.io.IOException {
        owned(user,collection);
        var header=mapper.find(id,user,collection).orElseThrow(ContentPages::notFound);
        var snapshot=(ObjectNode)json.readTree(header.collectionSnapshot());
        var response=json.createObjectNode().put("id",id).put("mode","practice").put("status","completed")
                .put("snapshotVersion",snapshot.path("snapshotVersion").asInt()).put("collectionTitle",snapshot.path("title").asString())
                .put("createdAt",header.createdAt().toString()).put("updatedAt",header.updatedAt().toString());
        var summary=json.readTree(header.summary());response.set("summary",summary);
        for(String name:java.util.List.of("questionCount","submittedCount","pendingCount","score","maxScore"))response.set(name,summary.path(name));
        var entries=mapper.entries(id,user);var questions=response.putArray("questions");var pageValues=response.putObject("pages");
        Map<String,PracticeHistoryMapper.Entry> byId=new LinkedHashMap<>();for(var entry:entries)byId.put(entry.questionId(),entry);
        boolean legacy=snapshot.path("snapshotVersion").asInt()<1;
        var order=json.createArrayNode();
        for(var question:snapshot.path("questions")) {
            var entry=byId.get(question.path("id").asString());
            if(entry==null) { if(legacy)continue;throw new IllegalStateException("历史快照不完整"); }
            order.add(question);
            var payload=(ObjectNode)json.readTree(entry.payload());
            var board=payload.path("whiteboard");
            var annotation=mapper.annotation(id,entry.questionId(),user).orElse(null);
            if(board.isObject()&&annotation!=null) {
                ((ObjectNode)board).set("notes",json.readTree(annotation.notes()));
                ((ObjectNode)board).put("revision",annotation.revision());
            } else if(board.isObject()) ((ObjectNode)board).put("revision",0);
            String digest=HexFormat.of().formatHex(entry.extensionDigest());
            if(!pageValues.has(digest)) {
                try {
                    var page=new LinkedHashMap<>(pages.page(user,digest,payload.path("extension").path("version").asString()));
                    page.put("extension",payload.path("extension"));pageValues.set(digest,json.valueToTree(page));
                } catch(ApiException|java.io.IOException unavailable) {
                    // 管理员移除文件后，仍保留其余冻结信息。
                    var page=json.createObjectNode();page.set("apiVersion",json.valueToTree(Map.of("major",1,"minor",5)));
                    page.set("extension",payload.path("extension"));page.put("html","<p>该资源已被管理员移除，题目结构、作答与评分仍保留。</p><h3 id=title></h3><pre id=answer></pre><pre id=result></pre>");
                    page.put("script","QF.page.register({onLoad(value){document.getElementById('title').textContent=value.question.title;document.getElementById('answer').textContent=JSON.stringify(value.answer,null,2);document.getElementById('result').textContent=JSON.stringify(value.result,null,2);}});");
                    page.put("style","pre{white-space:pre-wrap;overflow-wrap:anywhere}");pageValues.set(digest,page);
                }
            }
            questions.addObject().put("pageKey",digest).set("payload",payload);
        }
        snapshot.set("questions",order);response.set("collection",snapshot);
        response.put("legacy",legacy);response.put("knownQuestionCount",questions.size());
        return response;
    }
    public ContentPages.FileResponse listening(String user,String collection,String id,String material) throws java.io.IOException {
        owned(user,collection);var header=mapper.find(id,user,collection).orElseThrow(ContentPages::notFound);
        var snapshot=json.readTree(header.collectionSnapshot());
        for(var item:snapshot.path("listeningSource").path("materials"))if(item.path("id").asString().equals(material))
            return pages.listening(user,snapshot.path("sourceBankId").asString(),item.path("file").asString());
        throw ContentPages.notFound();
    }
    public JsonNode annotate(String user,String collection,String id,String qid,String request,long revision,JsonNode document) {
        owned(user,collection);var next=boards.normalize(document);
        byte[] hash=hash(id+"\n"+qid+"\n"+revision+"\n"+json.writeValueAsString(next));
        try { return tx.execute(status->{
            mapper.lock(id,user,collection).orElseThrow(ContentPages::notFound);
            var replay=receipt(user,request,hash);if(replay!=null)return replay;
            var payload=json.readTree(mapper.payload(id,user,qid).orElseThrow(ContentPages::notFound));
            if(!payload.path("features").path("whiteboard").asBoolean(false))throw new ApiException(HttpStatus.FORBIDDEN,"FEATURE_DISABLED","题库未开放白板");
            var old=mapper.annotation(id,qid,user).orElse(null);
            if((old==null?0:old.revision())!=revision)throw new ApiException(HttpStatus.CONFLICT,"REVISION_CONFLICT","历史笔记已更新，请刷新重试");
            boards.requireSeparateLayers(payload.path("whiteboard").path("draft"),next);
            if(boards.bytes(next)+boards.bytes(payload.path("whiteboard").path("draft"))>WhiteboardDocuments.MAX_BYTES)throw WhiteboardDocuments.tooLarge();
            if(mapper.annotate(id,qid,user,json.writeValueAsString(next),revision+1,clock.instant())<1)throw new IllegalStateException("历史笔记未写入");
            var response=json.createObjectNode();var board=response.putObject("whiteboard");
            board.set("draft",payload.path("whiteboard").path("draft"));board.set("notes",next);board.put("revision",revision+1).put("draftLocked",true);
            receipts.insert(user,request,hash,json.writeValueAsString(response));return receipt(user,request,hash);
        }); } catch(org.springframework.dao.DuplicateKeyException error) { return recheck(user,request,hash,error); }
        catch(ApiException error) { if(error.status()!=HttpStatus.CONFLICT)throw error;return recheck(user,request,hash,error); }
    }
    public void delete(String user,String collection,String id) {
        owned(user,collection);
        tx.executeWithoutResult(status->{mapper.lock(id,user,collection).orElseThrow(ContentPages::notFound);
            mapper.deleteAnnotations(id,user);if(mapper.delete(id,user,clock.instant())!=1)throw new IllegalStateException("历史记录未删除");});
    }
    private JsonNode receipt(String user,String request,byte[] hash) {
        var row=receipts.find(user,request);if(row==null)return null;
        if(!Arrays.equals(hash,row.requestSha256()))throw new ApiException(HttpStatus.BAD_REQUEST,"REQUEST_ID_REUSED","请求 ID 已被重复使用");
        return json.readTree(row.response());
    }
    private JsonNode recheck(String user,String request,byte[] hash,RuntimeException error) {
        return tx.execute(status->{var value=receipt(user,request,hash);if(value==null)throw error;return value;});
    }
    private static byte[] hash(String value) { try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
}
