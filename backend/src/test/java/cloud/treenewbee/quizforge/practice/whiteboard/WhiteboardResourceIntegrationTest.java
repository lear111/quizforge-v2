package cloud.treenewbee.quizforge.practice.whiteboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import cloud.treenewbee.quizforge.practice.resources.ImageUploads;
import cloud.treenewbee.quizforge.rules.*;
import cloud.treenewbee.quizforge.shared.id.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(WhiteboardResourceIntegrationTest.Configuration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class WhiteboardResourceIntegrationTest extends MySqlTestBase {
    static final String ORIGIN="https://whiteboard.example.test";
    static final Path ROOT=temp();
    static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("qf.storage.blob-dir",()->ROOT.toString());
        registry.add("qf.security.allowed-origin",()->ORIGIN);
    }
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired SqlGate gate;
    @MockitoSpyBean BlobStore blobs;
    @MockitoSpyBean PracticeReadMapper readMapper;
    @MockitoSpyBean WhiteboardMapper boards;
    @MockitoSpyBean cloud.treenewbee.quizforge.practice.infrastructure.ReceiptMapper receipts;
    @MockitoBean RuleRunnerClient runner;
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    Viewer owner;
    record Viewer(String user,String cookie,String collection,String bank,String context){
        String path(){return "/api/collections/bank/"+collection+"/questions/q1";}
    }
    @BeforeEach void setup() throws Exception{
        gate.active.set(null);reset(runner);
        when(runner.execute(anyString(),any(),anyMap())).thenReturn(RuleResult.success(json.readTree("{\"projected\":{\"stem\":\"可见题干\"}}")));
        owner=viewer();clearInvocations(readMapper,runner,blobs,boards);
    }
    @AfterEach void clearGate(){gate.active.set(null);}

    @Test void uploadSniffsMimeIsUserIdempotentAndDoesNotConsumeQuota() throws Exception{
        jdbc.update("INSERT INTO quota_account (user_id,storage_limit,storage_used) VALUES (?,0,0)",owner.user());
        var first=upload(owner,PNG,"text/html",null);assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var body=json.readTree(first.body());assertThat(body.path("mime").asString()).isEqualTo("image/png");assertThat(body.path("size").asInt()).isEqualTo(PNG.length);
        var again=upload(owner,PNG,"image/jpeg","true");assertThat(again.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_user_resource WHERE user_id=?",Long.class,owner.user())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT storage_used FROM quota_account WHERE user_id=?",Long.class,owner.user())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_storage_item WHERE user_id=?",Long.class,owner.user())).isZero();
    }
    @Test void forgedTypesOversizedBodiesAndInvalidTransientFlagsAreRejected() throws Exception{
        error(upload(owner,"<script>bad</script>".getBytes(),"image/png",null),415,"IMAGE_TYPE_UNSUPPORTED");
        error(upload(owner,new byte[ImageUploads.MAX_BYTES+1],"image/png",null),413,"IMAGE_SIZE_LIMIT");
        error(upload(owner,PNG,"image/png","false"),400,"VALIDATION_FAILED");
        error(send("POST","/api/resources",owner.cookie(),json.writeValueAsString(Map.of("data","a===")),null),400,"VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_user_resource WHERE user_id=?",Long.class,owner.user())).isZero();
    }
    @Test void resourcesUseNginxHandoffAndHashesCannotReadOrReferenceAnotherUsersImage() throws Exception{
        String id=json.readTree(upload(owner,PNG,"image/png",null).body()).path("id").asString();
        var another=viewer();clearInvocations(blobs);
        var own=send("GET","/api/resources/"+id,owner.cookie(),null,null);
        assertThat(own.statusCode()).isEqualTo(200);assertThat(own.body()).isEmpty();
        assertThat(own.headers().firstValue("X-Accel-Redirect")).contains("/_qf_blobs/"+id.substring(0,2)+"/"+id.substring(2,4)+"/"+id);
        assertThat(own.headers().firstValue("Cache-Control")).contains("private, no-store");
        verify(blobs,never()).open(anyString());
        error(send("GET","/api/resources/"+id,another.cookie(),null,null),404,"NOT_FOUND");
        var answer=json.readTree("{\"type\":\"image\",\"attrs\":{\"assetId\":\""+id+"\"}}");
        error(action(another,"draft","foreign-image",0,Map.of("answer",answer)),422,"IMAGE_REFERENCE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Long.class,another.user())).isZero();
        // Uploading the same bytes explicitly grants this user ownership, independent of global CAS de-duplication.
        assertThat(upload(another,PNG,"image/png",null).statusCode()).isEqualTo(200);
        assertThat(send("GET","/api/resources/"+id,another.cookie(),null,null).statusCode()).isEqualTo(200);
    }
    @Test void currentAnswerMayReferenceAtMostTwentyDistinctImages() throws Exception{
        var images=json.createArrayNode();for(int i=0;i<21;i++)images.addObject().putObject("attrs").put("assetId",String.format("%064x",i));
        error(action(owner,"draft","too-many-images",0,Map.of("answer",images)),413,"IMAGE_COUNT_LIMIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Long.class,owner.user())).isZero();
    }
    @Test void boardIsTwoLayersAndIndependentOfAnswerRevisionWithoutBankOrRunnerWork() throws Exception{
        jdbc.update("INSERT INTO quota_account (user_id,storage_limit,storage_used) VALUES (?,0,0)",owner.user());
        var first=ok(board(owner,"first",0,"draft",document("a")));
        assertThat(first.path("whiteboard").path("revision").asLong()).isEqualTo(1);
        ok(board(owner,"notes",1,"notes",document("n")));
        ok(board(owner,"edit-draft",2,"draft",document("b")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_question_state WHERE user_id=?",Long.class,owner.user())).isZero();
        assertThat(jdbc.queryForObject("SELECT progress_version FROM practice_context WHERE id=?",Long.class,owner.context())).isZero();
        verify(readMapper,never()).questions(anyString(),anyString(),anyString());verifyNoInteractions(runner);
        assertThat(jdbc.queryForObject("SELECT storage_used FROM quota_account WHERE user_id=?",Long.class,owner.user())).isZero();
        var read=ok(send("GET",owner.path(),owner.cookie(),null,null));
        assertThat(read.path("whiteboard").path("draft").path("strokes").get(0).path("id").asString()).isEqualTo("b");
        assertThat(read.path("whiteboard").path("notes").path("strokes").get(0).path("id").asString()).isEqualTo("n");
        assertThat(read.path("capabilities").path("canWhiteboard").asBoolean()).isTrue();
        var state=jdbc.queryForMap("SELECT size_bytes,revision,updated_at FROM practice_whiteboard WHERE context_id=?",owner.context());
        assertThat(((Number)state.get("revision")).longValue()).isEqualTo(3);assertThat(((Number)state.get("size_bytes")).intValue()).isPositive();
    }
    @Test void noteDeletionAndModificationAreRejectedButAppendingSucceeds() throws Exception{
        ok(board(owner,"note",0,"notes",document("n")));
        error(board(owner,"erase",1,"notes",document()),409,"WHITEBOARD_NOTES_APPEND_ONLY");
        var changed=document("n");((tools.jackson.databind.node.ObjectNode)changed.path("strokes").get(0)).put("width",9);
        error(board(owner,"modify",1,"notes",changed),409,"WHITEBOARD_NOTES_APPEND_ONLY");
        ok(board(owner,"append",1,"notes",document("n","next")));
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_whiteboard WHERE context_id=?",Long.class,owner.context())).isEqualTo(2);
    }
    @Test void submittedQuestionDoesNotLockBoardButCompletedRoundLocksDraftOnly() throws Exception{
        String round=Ids.uuidV7();
        jdbc.update("INSERT INTO practice_round (id,user_id,collection_id,context_id,mode,status,collection_snapshot,created_at,updated_at) VALUES (?,?,?,?,'practice','in_progress','{}',NOW(3),NOW(3))",round,owner.user(),owner.collection(),owner.context());
        jdbc.update("UPDATE practice_context SET current_round_id=? WHERE id=?",round,owner.context());
        jdbc.update("INSERT INTO practice_question_state (context_id,question_id,user_id,status,revision,content_sha256,updated_at) VALUES (?,'q1',?,'submitted',5,UNHEX(REPEAT('aa',32)),NOW(3))",owner.context(),owner.user());
        ok(board(owner,"submitted-draft",0,"draft",document("a")));
        ok(board(owner,"submitted-note",1,"notes",document("n")));
        jdbc.update("UPDATE practice_round SET status='completed' WHERE id=?",round);
        error(board(owner,"locked",2,"draft",document()),409,"WHITEBOARD_DRAFT_LOCKED");
        ok(board(owner,"completed-note",2,"notes",document("n","more")));
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_question_state WHERE context_id=?",Long.class,owner.context())).isEqualTo(5);
    }
    @Test void sameRequestReplaysOriginalBoardAndReusedPayloadOrStaleVersionCannotWrite() throws Exception{
        var original=board(owner,"same",0,"draft",document("first"));ok(original);
        ok(board(owner,"next",1,"draft",document("next")));
        assertThat(board(owner,"same",0,"draft",document("first")).body()).isEqualTo(original.body());
        error(board(owner,"same",0,"draft",document("different")),400,"REQUEST_ID_REUSED");
        error(board(owner,"stale",1,"draft",document("stale")),409,"REVISION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_whiteboard WHERE context_id=?",Long.class,owner.context())).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(longs={0,1})
    void trulyConcurrentSameIdFirstAndLaterSavesBothReplayOneWrite(long revision) throws Exception{
        if(revision==1)ok(board(owner,"seed",0,"draft",document("seed")));
        var sync=new SqlGate.Barrier(owner.context());gate.active.set(sync);
        try(var workers=Executors.newFixedThreadPool(2)){
            var a=workers.submit(()->board(owner,"concurrent",revision,"draft",document("same")));
            var b=workers.submit(()->board(owner,"concurrent",revision,"draft",document("same")));
            assertThat(sync.ready.await(20,TimeUnit.SECONDS)).isTrue();sync.go.countDown();
            var one=a.get(30,TimeUnit.SECONDS);var two=b.get(30,TimeUnit.SECONDS);ok(one);ok(two);assertThat(two.body()).isEqualTo(one.body());
        }finally{sync.go.countDown();}
        assertThat(jdbc.queryForObject("SELECT revision FROM practice_whiteboard WHERE context_id=?",Long.class,owner.context())).isEqualTo(revision+1);
    }
    @Test void concurrentDifferentIdsWithSameBoardVersionHaveExactlyOneWinner() throws Exception{
        var sync=new SqlGate.Barrier(owner.context());gate.active.set(sync);
        try(var workers=Executors.newFixedThreadPool(2)){
            var a=workers.submit(()->board(owner,"one",0,"draft",document("a")));
            var b=workers.submit(()->board(owner,"two",0,"draft",document("b")));
            assertThat(sync.ready.await(20,TimeUnit.SECONDS)).isTrue();sync.go.countDown();
            assertThat(List.of(a.get(30,TimeUnit.SECONDS).statusCode(),b.get(30,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
        }finally{sync.go.countDown();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_whiteboard WHERE context_id=?",Long.class,owner.context())).isEqualTo(1);
    }
    @Test void boardIsRolledBackIfReceiptWriteFailsAndCannotCrossUserBoundary() throws Exception{
        var another=viewer();
        error(action(new Viewer(another.user(),another.cookie(),owner.collection(),owner.bank(),owner.context()),"whiteboard","idor",0,Map.of("draft",document())),404,"NOT_FOUND");
        // Failure occurs after the real board INSERT, in the same MySQL transaction.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test receipt failure"))
                .when(receipts).insert(eq(owner.user()),eq("receipt-failure"),any(),anyString());
        assertThat(board(owner,"receipt-failure",0,"draft",document("x")).statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_whiteboard WHERE context_id=?",Long.class,owner.context())).isZero();
    }
    @Test void disabledFeatureAndOversizedLayerCannotSave() throws Exception{
        jdbc.update("UPDATE content_bank SET features='{\"whiteboard\":false}' WHERE id=?",owner.bank());
        error(board(owner,"disabled",0,"draft",document()),403,"FEATURE_DISABLED");
        jdbc.update("UPDATE content_bank SET features='{\"whiteboard\":true}' WHERE id=?",owner.bank());
        var big=document();big.put("extra","x".repeat(WhiteboardDocuments.MAX_BYTES));
        error(board(owner,"large",0,"draft",big),413,"DRAFT_SIZE_LIMIT");
    }
    @Test void answerStampCannotHideANewerBoardRevision() throws Exception{
        var initial=ok(send("GET",owner.path(),owner.cookie(),null,null));
        String stamp=json.writeValueAsString(initial.get("stamp"));
        ok(board(owner,"new-board",0,"draft",document("saved")));
        var read=send("GET",owner.path(),owner.cookie(),null,Map.of("X-QuizForge-Question-Stamp",stamp));
        assertThat(ok(read).path("whiteboard").path("revision").asLong()).isEqualTo(1);
        var known=initial.get("stamp").deepCopy();((tools.jackson.databind.node.ObjectNode)known).put("whiteboardRevision",1);
        assertThat(ok(send("GET",owner.path(),owner.cookie(),null,Map.of("X-QuizForge-Question-Stamp",json.writeValueAsString(known)))).path("unchanged").asBoolean()).isTrue();
    }
    @Test void oneBoardSaveOnThreeHundredQuestionsDoesNotEnumerateOrModifyTheBank() throws Exception{
        for(int i=2;i<=300;i++)jdbc.update("INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256) SELECT bank_id,?,?,'perf',extension_digest,data,max_score,content_sha256 FROM content_question WHERE bank_id=? AND question_id='q1'","q"+i,i,owner.bank());
        clearInvocations(readMapper,runner);
        var sql=new ConcurrentLinkedQueue<String>();gate.captured.set(sql);
        long started=System.nanoTime();
        try { ok(board(owner,"performance",0,"draft",document("ink"))); }
        finally { gate.captured.set(null); }
        long elapsed=TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-started);
        assertThat(sql.stream().filter(item->!item.startsWith("SELECT:")).toList())
                .containsExactly("INSERT:WhiteboardMapper.insert","INSERT:ReceiptMapper.insert");
        System.out.println("WHITEBOARD_300_QUESTIONS_SQL="+sql);
        System.out.println("WHITEBOARD_300_QUESTIONS_HTTP_MICROS="+elapsed);
        verify(readMapper,never()).questions(anyString(),anyString(),anyString());verify(readMapper,never()).collections(anyString());verifyNoInteractions(runner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_whiteboard WHERE context_id=?",Long.class,owner.context())).isEqualTo(1);
    }

    private Viewer viewer() throws Exception{
        var user=auth.register(Ids.uuidV7()+"@example.test","白板测试","password-123","test");
        String bank=Ids.uuidV7(),collection=Ids.uuidV7(),context=Ids.uuidV7();String digest=Ids.uuidV7().replace("-","")+"a".repeat(32);
        jdbc.update("INSERT INTO content_extension_package (digest,extension_id,version,name,manifest,api_major,api_min_minor,created_at) VALUES (UNHEX(?),'test.board','1','白板测试',?,1,0,NOW(3))",digest,"{\"rules\":\"rules.js\",\"questionSchema\":\"q.json\",\"answerSchema\":\"a.json\",\"requiresApi\":{\"major\":1,\"minMinor\":0,\"capabilities\":[]}}");
        for(String file:List.of("rules.js","q.json","a.json"))jdbc.update("INSERT INTO content_extension_file VALUES (UNHEX(?),?,UNHEX(REPEAT('bb',32)))",digest,file);
        jdbc.update("INSERT INTO admin_extension_review (digest,extension_id,version,status) VALUES (UNHEX(?),'test.board','1','approved')",digest);
        jdbc.update("INSERT INTO content_bank VALUES (?,UNHEX(?),'白板测试',NULL,2,'{\"whiteboard\":true}',NULL,1,NOW(3))",bank,Ids.uuidV7().replace("-","")+"b".repeat(32));
        jdbc.update("INSERT INTO content_question (bank_id,question_id,position,title,extension_digest,data,max_score,content_sha256) VALUES (?,'q1',0,'测试题',UNHEX(?),'{}',1,UNHEX(REPEAT('aa',32)))",bank,digest);
        jdbc.update("INSERT INTO practice_collection (id,user_id,bank_id,title,source,created_at) VALUES (?,?,?,'白板测试','{}',NOW(3))",collection,user.userId(),bank);
        jdbc.update("INSERT INTO practice_context (id,user_id,collection_id,kind,scope_key,created_at,updated_at) VALUES (?,?,?,'practice','default',NOW(3),NOW(3))",context,user.userId(),collection);
        return new Viewer(user.userId(),"__Host-qf_session="+user.token(),collection,bank,context);
    }
    private tools.jackson.databind.node.ObjectNode document(String... ids){
        var doc=new WhiteboardDocuments(json).empty();var strokes=(tools.jackson.databind.node.ArrayNode)doc.get("strokes");
        for(String id:ids)strokes.add(json.readTree("{\"id\":\""+id+"\",\"color\":\"#334155\",\"width\":3,\"points\":[{\"x\":10,\"y\":20,\"pressure\":0.5}]}"));return doc;
    }
    private HttpResponse<String> board(Viewer user,String request,long revision,String layer,JsonNode draft)throws Exception{return action(user,"whiteboard",request,revision,Map.of("draft",draft,"layer",layer));}
    private HttpResponse<String> action(Viewer user,String action,String request,long revision,Map<String,?> data)throws Exception{return send("POST",user.path()+"/actions",user.cookie(),json.writeValueAsString(Map.of("requestId",request,"revision",revision,"action",action,"data",data)),null);}
    private HttpResponse<String> upload(Viewer user,byte[] bytes,String mime,String flag)throws Exception{return send("POST","/api/resources",user.cookie(),json.writeValueAsString(Map.of("mime",mime,"data",Base64.getEncoder().encodeToString(bytes))),flag==null?null:Map.of("X-QuizForge-Transient-Resource",flag));}
    private HttpResponse<String> send(String method,String path,String cookie,String body,Map<String,String> extra)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(30)).header("Cookie",cookie).header("Origin",ORIGIN);
        if(extra!=null)extra.forEach(request::header);
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private void error(HttpResponse<String> response,int status,String code){assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(json.readTree(response.body()).path("error").path("code").asString()).isEqualTo(code);}
    static Path temp(){try{return Files.createTempDirectory("qf-whiteboard-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}

    @TestConfiguration(proxyBeanMethods=false) static class Configuration{@Bean SqlGate whiteboardGate(){return new SqlGate();}}
    @Intercepts({@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
            @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})})
    static class SqlGate implements Interceptor{
        static class Barrier{final String context;final CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);Barrier(String context){this.context=context;}}
        final AtomicReference<Barrier> active=new AtomicReference<>();
        final AtomicReference<Queue<String>> captured=new AtomicReference<>();
        public Object intercept(Invocation call)throws Throwable{
            var current=active.get();var statement=(MappedStatement)call.getArgs()[0];
            var recording=captured.get();
            if(recording!=null){String name=statement.getId();recording.add(statement.getSqlCommandType()+":"+name.substring(name.lastIndexOf('.',name.lastIndexOf('.')-1)+1));}
            if(current!=null&&statement.getId().endsWith("PracticeContextMapper.lockForUpdate")&&call.getArgs()[1] instanceof Map<?,?> parameters&&current.context.equals(parameters.get("contextId"))){
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                current.ready.countDown();if(!current.go.await(25,TimeUnit.SECONDS))throw new AssertionError("context gate timed out");
            }
            return call.proceed();
        }
    }
}
