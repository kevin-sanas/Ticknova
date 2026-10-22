package com.ticknova.knowledge;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.*;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;
import static com.ticknova.knowledge.KnowledgeDtos.*;

@Service
public class KnowledgeAuthoringService {
    private final JdbcClient jdbc;private final KnowledgeService knowledge;private final KnowledgeRepository repo;private final RichDocument rich;private final ObjectMapper json;private final IdempotencyService idem;private final AuditService audit;private final UploadStorage storage;
    public KnowledgeAuthoringService(JdbcClient jdbc,KnowledgeService knowledge,KnowledgeRepository repo,RichDocument rich,ObjectMapper json,IdempotencyService idem,AuditService audit,UploadStorage storage){this.jdbc=jdbc;this.knowledge=knowledge;this.repo=repo;this.rich=rich;this.json=json;this.idem=idem;this.audit=audit;this.storage=storage;}
    public List<Category> categories(Actor a,UUID space){if(!repo.space(a,space,false).canRead())throw KnowledgeRepository.notFound();return categoryRows(space);}
    private List<Category> categoryRows(UUID space){return jdbc.sql("SELECT id,space_id,parent_id,name,version FROM knowledge_category WHERE space_id=:space ORDER BY lower(name),id").param("space",space).query((r,n)->new Category(r.getObject("id",UUID.class),r.getObject("space_id",UUID.class),r.getObject("parent_id",UUID.class),r.getString("name"),r.getInt("version"))).list();}
    private Category category(UUID id){return jdbc.sql("SELECT id,space_id,parent_id,name,version FROM knowledge_category WHERE id=:id").param("id",id).query((r,n)->new Category(r.getObject("id",UUID.class),r.getObject("space_id",UUID.class),r.getObject("parent_id",UUID.class),r.getString("name"),r.getInt("version"))).optional().orElseThrow(KnowledgeRepository::notFound);}
    private void name(String value){if(value==null||value.isBlank()||value.length()>160)throw new ApiException(400,"INVALID_CATEGORY_NAME","分类名称需要1至160字符");}
    private void title(String value){if(value==null||value.isBlank()||value.length()>240)throw new ApiException(400,"INVALID_DOCUMENT_TITLE","标题需要1至240字符");}
    private ApiException conflict(){return new ApiException(409,"VERSION_CONFLICT","内容已被其他编辑者修改，请重新加载后合并");}
    void validCategory(UUID space,UUID id){if(id!=null&&!category(id).spaceId().equals(space))throw new ApiException(400,"CATEGORY_SPACE_MISMATCH","分类必须属于同一空间");}
    private void tree(UUID space,UUID moving,UUID parent){
        var rows=categoryRows(space);var map=new HashMap<UUID,UUID>();for(var row:rows)map.put(row.id(),row.parentId());
        if(parent!=null&&!map.containsKey(parent))throw new ApiException(400,"CATEGORY_SPACE_MISMATCH","父分类必须属于同一空间");map.put(moving,parent);
        for(UUID start:map.keySet()){var seen=new HashSet<UUID>();UUID current=start;int depth=0;while(current!=null){if(!seen.add(current))throw new ApiException(409,"CATEGORY_CYCLE","分类不能形成循环");if(++depth>5)throw new ApiException(409,"CATEGORY_TOO_DEEP","分类最多支持五层");current=map.get(current);}}
    }
    @Transactional public Category createCategory(Actor a,UUID space,String name,UUID parent){knowledge.editableSpace(a,space);name(name);UUID id=UUID.randomUUID();tree(space,id,parent);jdbc.sql("INSERT INTO knowledge_category(id,space_id,parent_id,name) VALUES(:id,:space,:parent,:name)").param("id",id).param("space",space).param("parent",parent).param("name",name.trim()).update();audit.record(a,"KNOWLEDGE_CATEGORY_CREATED","KNOWLEDGE_CATEGORY",id,Map.of("spaceId",space.toString()));return category(id);}
    @Transactional public Category patchCategory(Actor a,UUID id,JsonNode body){
        knowledge.requireCurrentEditor(a);var before=category(id);knowledge.editableSpace(a,before.spaceId());var current=category(id);
        if(body==null||!body.isObject()||!body.path("version").isIntegralNumber())throw new ApiException(400,"INVALID_CATEGORY_UPDATE","请提供分类版本");
        for(String field:body.propertyNames())if(!Set.of("name","parentId","version").contains(field))throw new ApiException(400,"INVALID_CATEGORY_UPDATE","分类更新字段无效");
        if(current.version()!=body.path("version").asInt())throw conflict();String name=body.has("name")?body.path("name").asText(null):current.name();name(name);
        UUID parent=current.parentId();if(body.has("parentId")){try{parent=body.path("parentId").isNull()?null:UUID.fromString(body.path("parentId").asText());}catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_CATEGORY_PARENT","父分类无效");}}
        tree(current.spaceId(),id,parent);
        jdbc.sql("UPDATE knowledge_category SET name=:name,parent_id=:parent,version=version+1 WHERE id=:id AND version=:version").param("name",name.trim()).param("parent",parent).param("id",id).param("version",current.version()).update();audit.record(a,"KNOWLEDGE_CATEGORY_CHANGED","KNOWLEDGE_CATEGORY",id,Map.of("spaceId",current.spaceId().toString(),"version",current.version()+1));return category(id);
    }
    @Transactional public void deleteCategory(Actor a,UUID id,int expected){
        knowledge.requireCurrentEditor(a);var before=category(id);knowledge.editableSpace(a,before.spaceId());var current=category(id);if(current.version()!=expected)throw conflict();
        boolean occupied=jdbc.sql("SELECT EXISTS(SELECT 1 FROM knowledge_category WHERE parent_id=:id UNION ALL SELECT 1 FROM knowledge_document WHERE category_id=:id UNION ALL SELECT 1 FROM knowledge_draft WHERE category_id=:id UNION ALL SELECT 1 FROM document_version WHERE authored_category_id=:id)").param("id",id).query(Boolean.class).single();
        if(occupied)throw new ApiException(409,"CATEGORY_NOT_EMPTY","分类含有子分类或文档，不能删除");jdbc.sql("DELETE FROM knowledge_category WHERE id=:id AND version=:version").param("id",id).param("version",expected).update();audit.record(a,"KNOWLEDGE_CATEGORY_DELETED","KNOWLEDGE_CATEGORY",id,Map.of("spaceId",current.spaceId().toString()));
    }
    private record ArticleRequest(String title,UUID categoryId,JsonNode content){}
    @Transactional public Draft create(Actor a,UUID space,String title,UUID category,JsonNode content,String key){
        knowledge.editableSpace(a,space);title(title);validCategory(space,category);var normalized=rich.normalize(content);
        return idem.execute(a,"KNOWLEDGE_ARTICLE_CREATE:"+space,key,new ArticleRequest(title.trim(),category,normalized),201,request->{
            UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO knowledge_document(id,space_id,title,category_id,created_by) VALUES(:id,:space,:title,:category,:actor)").param("id",id).param("space",space).param("title",title.trim()).param("category",category).param("actor",a.id()).update();
            insertDraft(a,id,title.trim(),category,normalized);audit.record(a,"KNOWLEDGE_ARTICLE_CREATED","KNOWLEDGE_DOCUMENT",id,Map.of("spaceId",space.toString()));return new IdempotencyService.Created<>(id,storedDraft(id,space).orElseThrow());
        },id->{knowledge.editable(a,id);return storedDraft(id,space).orElseThrow(KnowledgeRepository::notFound);});
    }
    private Optional<Draft> storedDraft(UUID id,UUID space){return jdbc.sql("SELECT * FROM knowledge_draft WHERE document_id=:id").param("id",id).query((r,n)->new Draft(id,space,r.getString("title"),r.getObject("category_id",UUID.class),json.readTree(r.getString("content")),r.getInt("version"),r.getTimestamp("updated_at").toInstant(),r.getObject("updated_by",UUID.class))).optional();}
    private void requireDraftRead(Actor a,Summary d){if(!d.canEdit())throw new ApiException(403,"SPACE_EDIT_FORBIDDEN","仅空间编辑者可读取草稿");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Draft draft(Actor a,UUID id){var d=repo.document(a,id,false);requireDraftRead(a,d);return storedDraft(id,d.spaceId()).orElseGet(()->sourceDraft(a,d));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public DraftSource draftSource(Actor a,UUID id,UUID version){
        var d=repo.document(a,id,false);requireDraftRead(a,d);
        var selected=repo.versions(a,id).stream().filter(v->v.id().equals(version)).findFirst().orElseThrow(KnowledgeRepository::notFound);
        if(!selected.ingestState().equals("READY")||selected.chunkCount()<1)throw new ApiException(409,"VERSION_NOT_READY","解析完成后才能导入正文");
        return buildDraftSource(a,d,selected);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public JsonNode versionContent(Actor a,UUID id,UUID version){
        knowledge.readableVersion(a,id,version);
        return jdbc.sql("SELECT rich_content FROM document_version WHERE id=:version AND document_id=:doc").param("version",version).param("doc",id)
            .query((r,n)->{String value=r.getString("rich_content");return value==null?json.readTree("null"):rich.normalize(json.readTree(value));}).optional().orElseThrow(KnowledgeRepository::notFound);
    }
    private DraftSource buildDraftSource(Actor a,Summary d,Version version){
        JsonNode snapshot=jdbc.sql("SELECT rich_content FROM document_version WHERE id=:version AND document_id=:doc")
            .param("version",version.id()).param("doc",d.id()).query((r,n)->{String value=r.getString("rich_content");return value==null?null:json.readTree(value);}).optional().orElse(null);
        if(snapshot!=null)return new DraftSource(version.id(),version.versionNo(),true,null,rich.normalize(snapshot));
        StringBuilder merged=new StringBuilder();Integer previousPage=null;
        for(var chunk:knowledge.chunks(a,d.id(),version.id())){
            String next=chunk.content().strip();if(next.isEmpty())continue;
            if(merged.isEmpty())merged.append(next);
            else {
                boolean newPage=previousPage!=null&&chunk.pageNo()!=null&&!previousPage.equals(chunk.pageNo());
                int overlap=newPage?0:overlap(merged,next);
                if(newPage||overlap==0)merged.append('\n');merged.append(next.substring(overlap));
            }
            previousPage=chunk.pageNo();
            if(merged.length()>100000)return new DraftSource(version.id(),version.versionNo(),false,"解析正文超过编辑器的 100000 字符上限，请阅读解析预览。",null);
        }
        String[] lines=merged.toString().split("\\n",-1);
        if(lines.length>5000)return new DraftSource(version.id(),version.versionNo(),false,"解析正文段落过多，无法安全导入编辑器，请阅读解析预览。",null);
        return new DraftSource(version.id(),version.versionNo(),true,null,rich.paragraphs(Arrays.asList(lines)));
    }
    private static int overlap(CharSequence current,String next){
        int[] prefix=new int[next.length()];
        for(int i=1,j=0;i<next.length();i++){
            while(j>0&&next.charAt(i)!=next.charAt(j))j=prefix[j-1];
            if(next.charAt(i)==next.charAt(j))j++;
            prefix[i]=j;
        }
        int matched=0,start=Math.max(0,current.length()-next.length());
        for(int i=start;i<current.length();i++){
            while(matched>0&&next.charAt(matched)!=current.charAt(i))matched=prefix[matched-1];
            if(next.charAt(matched)==current.charAt(i))matched++;
            if(matched==next.length()&&i<current.length()-1)matched=prefix[matched-1];
        }
        return matched>=4?matched:0;
    }
    private Draft sourceDraft(Actor a,Summary d){
        var versions=repo.versions(a,d.id());
        var selected=versions.stream().filter(v->v.id().equals(d.latestVersionId())&&v.ingestState().equals("READY")&&v.chunkCount()>0).findFirst()
            .or(()->versions.stream().filter(v->v.id().equals(d.publishedVersionId())&&v.ingestState().equals("READY")&&v.chunkCount()>0).findFirst());
        JsonNode content=selected.map(v->buildDraftSource(a,d,v)).filter(DraftSource::importable).map(DraftSource::content).orElseGet(()->rich.paragraphs(List.of()));
        UUID author=jdbc.sql("SELECT created_by FROM knowledge_document WHERE id=:id").param("id",d.id()).query(UUID.class).single();
        return new Draft(d.id(),d.spaceId(),d.title(),d.categoryId(),content,0,d.createdAt(),author);
    }
    private void insertDraft(Actor a,UUID id,String title,UUID category,JsonNode content){jdbc.sql("INSERT INTO knowledge_draft(document_id,title,category_id,content,updated_by) VALUES(:id,:title,:category,CAST(:content AS jsonb),:actor)").param("id",id).param("title",title).param("category",category).param("content",json.writeValueAsString(content)).param("actor",a.id()).update();}
    @Transactional public Draft save(Actor a,UUID id,String title,UUID category,JsonNode content,int expected){
        var d=knowledge.editable(a,id);title(title);validCategory(d.spaceId(),category);var normalized=rich.normalize(content);var current=storedDraft(id,d.spaceId());
        if(current.isEmpty()){if(expected!=0)throw conflict();insertDraft(a,id,title.trim(),category,normalized);}
        else {if(current.get().version()!=expected)throw conflict();int changed=jdbc.sql("UPDATE knowledge_draft SET title=:title,category_id=:category,content=CAST(:content AS jsonb),version=version+1,updated_at=now(),updated_by=:actor WHERE document_id=:id AND version=:version").param("title",title.trim()).param("category",category).param("content",json.writeValueAsString(normalized)).param("actor",a.id()).param("id",id).param("version",expected).update();if(changed!=1)throw conflict();}
        audit.record(a,"KNOWLEDGE_DRAFT_SAVED","KNOWLEDGE_DOCUMENT",id,Map.of("version",expected+1));return storedDraft(id,d.spaceId()).orElseThrow();
    }
    @Transactional public Upload commit(Actor a,UUID id,int expected,String key){
        var d=knowledge.editable(a,id);
        return idem.execute(a,"KNOWLEDGE_DRAFT_COMMIT:"+id,key,Map.of("version",expected),202,request->{
            Draft draft=storedDraft(id,d.spaceId()).orElseThrow(()->new ApiException(409,"DRAFT_SAVE_REQUIRED","请先保存草稿"));if(draft.version()!=expected)throw conflict();validCategory(d.spaceId(),draft.categoryId());
            var content=rich.normalize(draft.content());String markdown=rich.markdown(content);if(markdown.isBlank())throw new ApiException(400,"EMPTY_ARTICLE","文章内容不能为空");var validated=storage.validateMarkdown(markdown);
            String fingerprint;try{fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(new ArticleRequest(draft.title(),draft.categoryId(),content))));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            var upload=knowledge.persistUpload(a,d.spaceId(),id,draft.title(),draft.categoryId(),validated,content,fingerprint);
            audit.record(a,"KNOWLEDGE_DRAFT_COMMITTED","KNOWLEDGE_DOCUMENT",id,Map.of("draftVersion",expected,"versionId",upload.versionId().toString()));return new IdempotencyService.Created<>(upload.versionId(),upload);
        },version->{knowledge.editable(a,id);return jdbc.sql("SELECT document_id,id,job_id FROM document_version WHERE id=:v AND document_id=:doc").param("v",version).param("doc",id).query((r,n)->new Upload(r.getObject("document_id",UUID.class),r.getObject("id",UUID.class),r.getObject("job_id",UUID.class))).optional().orElseThrow(KnowledgeRepository::notFound);});
    }
}
