package cloud.treenewbee.quizforge.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cloud.treenewbee.quizforge.MySqlTestBase;
import cloud.treenewbee.quizforge.catalog.application.PlazaService;
import cloud.treenewbee.quizforge.catalog.infrastructure.*;
import cloud.treenewbee.quizforge.content.infrastructure.BlobStore;
import cloud.treenewbee.quizforge.identity.application.AuthService;
import cloud.treenewbee.quizforge.practice.application.LibraryPracticeService;
import cloud.treenewbee.quizforge.quota.application.QuotaService;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.id.Ids;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/** 合成元数据直接建库，验证 HTTP 安全与真实 MySQL；不依赖导入队列。 */
class PlazaIntegrationTest extends MySqlTestBase {
    @Autowired JdbcTemplate sql;
    @Autowired AuthService auth;
    @Autowired CatalogMapper catalog;
    @Autowired PlazaService plaza;
    @Autowired BlobStore blobs;
    @Autowired QuotaService quotas;
    @MockitoSpyBean RuleRunnerClient runner;
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();
    final JsonMapper json=JsonMapper.builder().build();
    record User(String id,String cookie) {}
    String owner;
    @BeforeEach void setup() { owner=user().id(); }
    User user() {
        var u=auth.register(Ids.uuidV7()+"@example.test","广场用户","password123",null);
        return new User(u.userId(),"__Host-qf_session="+u.token());
    }
    String pack(String title,String visibility,String status,long downloads) throws Exception {
        String id=Ids.uuidV7(); var now=Instant.parse("2026-11-01T00:00:00Z");
        catalog.insertPack(id,owner,now);
        String sha=blobs.put(new byte[]{1,2,3,4});
        catalog.insertRevision(new CatalogMapper.RevisionRow(id,1,title,"合成简介","公开说明","其他","[\"标签词\"]","更新说明","upload","原文件.qbank",HexFormat.of().parseHex(sha),4,"[]","pending",null,null,null,null,owner,now));
        sql.update("UPDATE catalog_pack SET approved_revision=1,visibility=?,status=?,download_count=? WHERE id=?",visibility,status,downloads,id);
        sql.update("UPDATE catalog_pack_revision SET review_status='approved' WHERE pack_id=?",id);
        return id;
    }
    HttpResponse<String> request(User user,String path,String method,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:8088").header("Content-Type","application/json");
        if(user!=null)builder.header("Cookie",user.cookie());
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void anonymousOnlySeesApprovedPublicVersionAndScreenshots() throws Exception {
        String id=pack("已审核标题","public","active",0);
        var now=Instant.now();
        catalog.insertRevision(new CatalogMapper.RevisionRow(id,2,"机密待审标题","机密简介","机密内容","其他","[]","机密更新","upload","秘密.qbank",new byte[32],1,"[]","pending",null,null,null,null,owner,now));
        sql.update("UPDATE catalog_pack SET latest_revision=2 WHERE id=?",id);
        catalog.insertFile(new CatalogMapper.FileRow(Ids.uuidV7(),id,2,"screenshot",0,"机密截图","image/png",1,new byte[32]));
        String file=catalog.files(id,2).getFirst().id();
        assertThat(request(null,"/api/plaza/packs/"+id,"GET",null).body()).contains("已审核标题").doesNotContain("机密","秘密");
        assertThat(plaza.list("机密待审标题","latest",null).items()).isEmpty();
        catalog.insertFile(new CatalogMapper.FileRow(Ids.uuidV7(),id,1,"screenshot",0,"公开截图","image/png",1,new byte[32]));
        assertThat(request(null,"/api/catalog/files/"+catalog.files(id,1).getFirst().id(),"GET",null).statusCode()).isEqualTo(200);
        assertThat(request(null,"/api/catalog/files/"+file,"GET",null).statusCode()).isEqualTo(404);
        for(String hidden:List.of(pack("私人","private","active",0),pack("已删除","public","deleted",0))) {
            assertThat(request(null,"/api/plaza/packs/"+hidden,"GET",null).statusCode()).isEqualTo(404);
            assertThat(request(null,"/api/plaza/packs/"+hidden+"/archive","GET",null).statusCode()).isEqualTo(404);
        }
        String pending=pack("待审","public","active",0);
        sql.update("UPDATE catalog_pack SET approved_revision=NULL WHERE id=?",pending);
        assertThat(request(null,"/api/plaza/packs/"+pending,"GET",null).statusCode()).isEqualTo(404);
    }
    @Test void searchLiteralWildcardsAndKeysetPaginationUseOnlyPublicFields() throws Exception {
        String term=Ids.uuidV7();
        for(int i=0;i<23;i++)pack(term+"-"+i,"public","active",i);
        var first=plaza.list(term,"latest",null);var second=plaza.list(term,"latest",first.next());
        assertThat(first.items()).hasSize(20);assertThat(second.items()).hasSize(3);
        assertThat(first.items().stream().map(PlazaMapper.Entry::id)).doesNotContainAnyElementsOf(second.items().stream().map(PlazaMapper.Entry::id).toList());
        var popular=plaza.list(term,"downloads",null);
        assertThat(popular.items()).extracting(PlazaMapper.Entry::downloadCount).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(plaza.list(term,"downloads",popular.next()).items()).hasSize(3);
        assertThat(plaza.list("%_not_a_wildcard","latest",null).items()).isEmpty();
        assertThat(plaza.list("标签词","latest",null).items()).isNotEmpty();
        assertThatThrownBy(()->plaza.list("","latest","invalid")).isInstanceOf(cloud.treenewbee.quizforge.shared.web.ApiException.class);
    }
    @Test void concurrentDownloadCountingIsAtomicAndAuthorizesOriginalWithoutJavaBody() throws Exception {
        String id=pack("下载","public","active",0);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);var calls=new ArrayList<Future<?>>();
            for(int i=0;i<30;i++)calls.add(threads.submit(()->{gate.await();return plaza.download(id);}));
            gate.countDown();for(var call:calls)call.get(20,TimeUnit.SECONDS);
        }
        var response=request(null,"/api/plaza/packs/"+id+"/archive","GET",null);
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("X-Accel-Redirect")).isPresent();
        assertThat(plaza.detail(null,id).pack().downloadCount()).isEqualTo(31);
    }
    @Test void favoritesAreUserOwnedIdempotentAndRetainDeletedPublicMetadata() throws Exception {
        var a=user();var b=user();String id=pack("收藏","public","active",0);
        String path="/api/plaza/packs/"+id+"/favorite";
        assertThat(request(null,path,"PUT",null).statusCode()).isEqualTo(401);
        assertThat(request(a,path,"PUT",null).statusCode()).isEqualTo(204);
        assertThat(request(a,path,"PUT",null).statusCode()).isEqualTo(204);
        assertThat(plaza.favorites(a.id(),null).items()).hasSize(1);
        assertThat(plaza.favorites(b.id(),null).items()).isEmpty();
        request(b,path,"DELETE",null);assertThat(plaza.favorites(a.id(),null).items()).hasSize(1);
        sql.update("UPDATE catalog_pack SET status='deleted' WHERE id=?",id);
        assertThat(plaza.favorites(a.id(),null).items().getFirst().status()).isEqualTo("deleted");
        assertThat(request(a,"/api/plaza/packs/"+id+"/imports","POST",Map.of("banks",List.of("x"))).statusCode()).isEqualTo(404);
        request(a,path,"DELETE",null);assertThat(plaza.favorites(a.id(),null).items()).isEmpty();
        assertThat(request(a,"/api/plaza/packs/"+pack("私人","private","active",0)+"/favorite","PUT",null).statusCode()).isEqualTo(404);
    }
    @Test void importsUseApprovedVersionCreateIndependentFreeInstancesWithoutRules() throws Exception {
        String id=pack("导入","public","active",0),bank=Ids.uuidV7(),uuid=Ids.uuidV7();
        sql.update("INSERT INTO content_bank(id,content_sha256,source_title,format_version,features,question_count,created_at) VALUES(?,UNHEX(SHA2(?,256)),'题库',2,'{}',0,NOW(3))",bank,bank);
        catalog.insertBanks(id,1,List.of(new CatalogMapper.PackBankRow(uuid,bank,"题库",0,0,null)));
        var learner=user();clearInvocations(runner);
        String path="/api/plaza/packs/"+id+"/imports";
        assertThat(request(null,path,"POST",Map.of("banks",List.of(uuid))).statusCode()).isEqualTo(401);
        var first=request(learner,path,"POST",Map.of("banks",List.of(uuid)));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(request(learner,path,"POST",Map.of("banks",List.of(uuid))).statusCode()).isEqualTo(200);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM practice_collection WHERE user_id=? AND source_pack_id=? AND source_bank_uuid=? AND source_revision=1",Integer.class,learner.id(),id,uuid)).isEqualTo(2);
        assertThat(quotas.usage(learner.id()).storage().usedBytes()).isZero();verifyNoInteractions(runner);
        assertThat(request(learner,"/api/catalog/packs/"+id+"/practice","POST",Map.of("banks",List.of(uuid))).statusCode()).isEqualTo(404);
    }

    @Test void favoritesPaginateAndShowTheNewApprovedVersionWithoutCopyingPendingMetadata() throws Exception {
        var learner=user();
        for(int i=0;i<22;i++)plaza.favorite(learner.id(),pack("分页收藏"+i,"public","active",0));
        var first=plaza.favorites(learner.id(),null);
        var second=plaza.favorites(learner.id(),first.next());
        assertThat(first.items()).hasSize(20);
        assertThat(second.items()).hasSize(2);
        assertThat(first.items().stream().map(PlazaMapper.Entry::id)).doesNotContainAnyElementsOf(second.items().stream().map(PlazaMapper.Entry::id).toList());
        String id=first.items().getFirst().id();
        catalog.insertRevision(new CatalogMapper.RevisionRow(id,2,"新审核版本","新简介","新内容","其他","[]","新更新","upload","原文件.qbank",new byte[32],1,"[]","pending",null,null,null,null,owner,Instant.now()));
        sql.update("UPDATE catalog_pack SET latest_revision=2 WHERE id=?",id);
        assertThat(plaza.favorites(learner.id(),null).items().getFirst().revision()).isEqualTo(1);
        sql.update("UPDATE catalog_pack_revision SET review_status='approved' WHERE pack_id=? AND revision=2",id);
        sql.update("UPDATE catalog_pack SET approved_revision=2 WHERE id=?",id);
        assertThat(plaza.favorites(learner.id(),null).items().getFirst().title()).isEqualTo("新审核版本");
    }
}
