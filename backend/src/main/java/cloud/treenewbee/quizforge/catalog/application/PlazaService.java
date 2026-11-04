package cloud.treenewbee.quizforge.catalog.application;

import cloud.treenewbee.quizforge.catalog.infrastructure.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlazaService {
    public record Page(List<PlazaMapper.Entry> items, String next) {}
    public record Detail(PlazaMapper.Entry pack, String description, String releaseNotes,
        List<CatalogPublishService.BankView> banks, List<CatalogPublishService.FileView> screenshots, boolean favorite) {}
    private final PlazaMapper plaza;
    private final CatalogMapper catalog;
    private final Clock clock;
    public PlazaService(PlazaMapper plaza,CatalogMapper catalog,Clock clock) {
        this.plaza=plaza; this.catalog=catalog; this.clock=clock;
    }
    public Page list(String query,String sort,String cursor) {
        if (query.length()>200 || !List.of("latest","downloads").contains(sort)) throw invalid();
        String after=null; long count=0; Instant time=Instant.EPOCH;
        if (cursor!=null) {
            if(cursor.length()>500) throw invalid();
            try {
                var fields=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
                if(fields.length!=4 || !fields[0].equals(sort)) throw invalid();
                after=UUID.fromString(fields[1]).toString(); count=Long.parseLong(fields[2]); time=Instant.parse(fields[3]);
                if(count<0) throw invalid();
            } catch (RuntimeException e) { throw invalid(); }
        }
        return page(plaza.list(query,sort.equals("downloads"),after,count,time),sort);
    }
    private Page page(List<PlazaMapper.Entry> rows,String sort) {
        var items=List.copyOf(rows.subList(0,Math.min(20,rows.size())));
        String next=null;
        if(rows.size()>20) {
            var last=items.getLast();
            next=sort.equals("favorites")?last.id():Base64.getUrlEncoder().withoutPadding().encodeToString(
                (sort+"|"+last.id()+"|"+last.downloadCount()+"|"+last.createdAt()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new Page(items,next);
    }
    public Page favorites(String user,String after) { return page(plaza.favorites(user,after),"favorites"); }
    public PlazaMapper.Entry visible(String id) { return plaza.visible(id).orElseThrow(PlazaService::notFound); }
    public Detail detail(String user,String id) {
        var pack=visible(id);
        var revision=catalog.revision(id,pack.revision()).orElseThrow(PlazaService::notFound);
        return new Detail(pack,revision.description(),revision.releaseNotes(),catalog.banks(id,pack.revision()).stream()
            .map(b->new CatalogPublishService.BankView(b.bankUuid(),b.title(),b.questionCount())).toList(),
            catalog.files(id,pack.revision()).stream().map(f->new CatalogPublishService.FileView(f.id(),f.name(),f.mime(),f.size())).toList(),
            user!=null && plaza.isFavorite(user,id)>0);
    }
    public CatalogMapper.RevisionRow download(String id) {
        var pack=visible(id);
        var revision=catalog.revision(id,pack.revision()).orElseThrow(PlazaService::notFound);
        if(revision.blobSha256()==null || plaza.download(id,pack.revision())!=1) throw notFound();
        return revision;
    }
    public void favorite(String user,String id) {
        visible(id);
        if (plaza.favorite(user,id,clock.instant()) == 0 && plaza.isFavorite(user,id) == 0) throw notFound();
    }
    public void unfavorite(String user,String id) { plaza.unfavorite(user,id); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","分页或搜索参数无效"); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"PACK_NOT_FOUND","题库包不存在或作者已删除"); }
}
