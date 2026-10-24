package com.ticknova.knowledge;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import static com.ticknova.knowledge.KnowledgeDtos.*;

@Service
public class KnowledgeService {
    private final JdbcClient jdbc;
    private final KnowledgeRepository repo;
    private final KnowledgePolicy policy;
    private final UploadStorage storage;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    public KnowledgeService(JdbcClient jdbc,KnowledgeRepository repo,KnowledgePolicy policy,UploadStorage storage,AuditService audit,IdempotencyService idempotency) {
        this.jdbc=jdbc;this.repo=repo;this.policy=policy;this.storage=storage;this.audit=audit;this.idempotency=idempotency;
    }
    public List<Space> spaces(Actor a) { return repo.spaces(a); }
    @Transactional public Space createSpace(Actor a,String name) {return createSpace(a,name,null);}
    @Transactional public Space createSpace(Actor a,String name,String key) {
        requireCurrentEditor(a); if(name==null||name.isBlank()||name.length()>160)throw new ApiException(400,"INVALID_SPACE_NAME","空间名称需要1至160字符");
        if(key!=null)return idempotency.execute(a,"KNOWLEDGE_SPACE_CREATE",key,new SpaceRequest(name.trim()),201,
            requestId->{var space=persistSpace(a,name);return new IdempotencyService.Created<>(space.id(),space);},id->repo.space(a,id,false));
        return persistSpace(a,name);
    }
    private record SpaceRequest(String name){}
    private Space persistSpace(Actor a,String name) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES(:id,:name,:actor)").param("id",id).param("name",name.trim()).param("actor",a.id()).update();
        jdbc.sql("INSERT INTO knowledge_space_member(space_id,user_id,can_read,can_edit) VALUES(:id,:user,true,true)").param("id",id).param("user",a.id()).update();
        audit.record(a,"KNOWLEDGE_SPACE_CREATED","KNOWLEDGE_SPACE",id,Map.of()); return repo.space(a,id,false);
    }
    private Space manager(Actor a,UUID space,boolean lock) {
        if(lock)requireCurrentEditor(a);else policy.requireEditor(a); var s=repo.space(a,space,lock);
        if(lock)s=repo.space(a,space,false);
        if(!s.canManageMembers())throw new ApiException(403,"SPACE_MANAGEMENT_FORBIDDEN","仅空间创建者或管理员可管理成员"); return s;
    }
    public List<Member> members(Actor a,UUID space) {
        manager(a,space,false);
        return jdbc.sql("SELECT u.id,u.username,u.display_name,m.can_read,m.can_edit FROM knowledge_space_member m JOIN app_user u ON u.id=m.user_id WHERE m.space_id=:space ORDER BY u.username")
            .param("space",space).query((r,n)->new Member(r.getObject("id",UUID.class),r.getString("username"),r.getString("display_name"),r.getBoolean("can_read"),r.getBoolean("can_edit"))).list();
    }
    public List<Candidate> candidates(Actor a,UUID space) {
        manager(a,space,false);
        return jdbc.sql("SELECT id,username,display_name FROM app_user WHERE active ORDER BY username LIMIT 1000")
            .query((r,n)->new Candidate(r.getObject("id",UUID.class),r.getString("username"),r.getString("display_name"))).list();
    }
    @Transactional public void membership(Actor a,UUID space,UUID user,boolean read,boolean edit,String reason) {
        manager(a,space,true);
        if(reason==null||reason.isBlank()||reason.length()>500)throw new ApiException(400,"ACL_REASON_REQUIRED","请填写1至500字符的权限变更理由");
        if(edit&&!read)throw new ApiException(400,"INVALID_SPACE_PERMISSION","编辑权限必须同时授予读取权限");
        boolean eligible=jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user u WHERE u.id=:user AND u.active AND (:edit=false OR EXISTS(SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code IN ('KB_EDITOR','ADMIN'))))")
            .param("user",user).param("edit",edit).query(Boolean.class).single();
        if(!eligible)throw new ApiException(400,"INVALID_SPACE_MEMBER","成员必须有效，编辑成员还需要知识编辑者或管理员角色");
        jdbc.sql("INSERT INTO knowledge_space_member(space_id,user_id,can_read,can_edit) VALUES(:space,:user,:read,:edit) ON CONFLICT(space_id,user_id) DO UPDATE SET can_read=excluded.can_read,can_edit=excluded.can_edit")
            .param("space",space).param("user",user).param("read",read).param("edit",edit).update();
        audit.record(a,"KNOWLEDGE_ACL_CHANGED","KNOWLEDGE_SPACE",space,Map.of("userId",user.toString(),"canRead",read,"canEdit",edit,"reason",reason.trim()));
    }
    public PageResult<Summary> documents(Actor a,UUID space,int page,int size,String q) {
        if(!repo.space(a,space,false).canRead())throw KnowledgeRepository.notFound(); return repo.documents(a,space,page,size,q);
    }
    public PageResult<Summary> documents(Actor a,UUID space,int page,int size,String q,UUID category) {
        if(!repo.space(a,space,false).canRead())throw KnowledgeRepository.notFound();return repo.documents(a,space,page,size,q,category);
    }
    public PageResult<ReviewTask> reviewTasks(Actor a,int page,int size){return repo.reviewTasks(a,page,size);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(Actor a,UUID doc) {
        var s=repo.document(a,doc,false); return detail(a,s);
    }
    private Detail detail(Actor a,Summary s) { return new Detail(s.id(),s.spaceId(),s.title(),s.publicationState(),s.latestVersionId(),s.publishedVersionId(),s.latestIngestState(),s.versionCount(),s.createdAt(),s.canEdit(),repo.versions(a,s.id()),s.categoryId()); }
    public Job job(Actor a,UUID id) { return repo.job(a,id); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Chunk> chunks(Actor a,UUID doc,UUID version) {
        var d=readableVersion(a,doc,version);
        return jdbc.sql("""
            SELECT c.* FROM knowledge_chunk c JOIN document_version v ON v.id=c.document_version_id
            JOIN knowledge_document d ON d.id=v.document_id JOIN knowledge_space s ON s.id=d.space_id
            JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user
            WHERE d.id=:doc AND c.document_version_id=:version AND m.can_read AND s.active AND d.publication_state<>'ARCHIVED'
              AND (:editor OR (d.publication_state='PUBLISHED' AND d.published_version_id=v.id AND v.ingest_state='READY' AND v.review_state='APPROVED')) ORDER BY c.chunk_index
            """).param("doc",doc).param("version",version).param("user",a.id()).param("editor",d.canEdit())
            .query((r,n)->new Chunk(r.getObject("id",UUID.class),r.getInt("chunk_index"),(Integer)r.getObject("page_no"),r.getString("heading_path"),r.getString("content"))).list();
    }
    Summary readableVersion(Actor a,UUID doc,UUID version) {
        var d=repo.document(a,doc,false);
        if(!d.canEdit()&&!(version.equals(d.publishedVersionId())&&d.publicationState().equals("PUBLISHED")))throw KnowledgeRepository.notFound();
        if(repo.versions(a,doc).stream().noneMatch(v->v.id().equals(version)))throw KnowledgeRepository.notFound();
        return d;
    }
    Summary editable(Actor a,UUID doc) {
        requireCurrentEditor(a); var before=repo.document(a,doc,false);
        repo.space(a,before.spaceId(),true); // All knowledge writes lock space before document; membership writes use the same lock.
        var current=repo.document(a,doc,true); if(!current.canEdit())throw new ApiException(403,"SPACE_EDIT_FORBIDDEN","没有此空间的编辑权限"); return current;
    }
    void requireCurrentEditor(Actor a) {
        policy.requireEditor(a);
        jdbc.sql("SELECT pg_advisory_xact_lock(873943625)").query((r,n)->true).list();
        boolean current=jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user u WHERE u.id=:u AND u.active AND EXISTS(SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code IN ('KB_EDITOR','ADMIN')))")
            .param("u",a.id()).query(Boolean.class).single();
        if(!current)throw new ApiException(403,"KNOWLEDGE_EDIT_FORBIDDEN","当前用户已失去知识编辑权限");
    }
    Space editableSpace(Actor a,UUID space) {
        requireCurrentEditor(a);repo.space(a,space,true);var s=repo.space(a,space,false);
        if(!s.canEdit())throw new ApiException(403,"SPACE_EDIT_FORBIDDEN","没有此空间的编辑权限");return s;
    }
    @Transactional public Upload upload(Actor a,UUID space,UUID doc,String title,MultipartFile file) {return upload(a,space,doc,title,file,null);}
    @Transactional public Upload upload(Actor a,UUID space,UUID doc,String title,MultipartFile file,String key) {
        requireCurrentEditor(a);
        if(doc!=null)space=editable(a,doc).spaceId();
        else {
            repo.space(a,space,true);
            // A lock wait does not refresh joined ACL rows in this statement's READ COMMITTED snapshot.
            var s=repo.space(a,space,false);
            if(!s.canEdit())throw new ApiException(403,"SPACE_EDIT_FORBIDDEN","没有此空间的编辑权限");
        }
        var valid=storage.validate(file);
        UUID targetSpace=space,targetDocument=doc;
        if(key!=null)return idempotency.execute(a,"KNOWLEDGE_UPLOAD:"+(doc==null?space:doc),key,new UploadFingerprint(space,doc,title==null?null:title.trim(),valid.name(),valid.mime(),valid.sha256()),202,
            requestId->{var created=persistUpload(a,targetSpace,targetDocument,title,valid);return new IdempotencyService.Created<>(created.versionId(),created);},id->{
                var saved=jdbc.sql("SELECT document_id,id,job_id FROM document_version WHERE id=:id").param("id",id)
                    .query((r,n)->new Upload(r.getObject("document_id",UUID.class),r.getObject("id",UUID.class),r.getObject("job_id",UUID.class))).optional().orElseThrow(KnowledgeRepository::notFound);
                editable(a,saved.documentId());return saved;
            });
        return persistUpload(a,space,doc,title,valid);
    }
    private record UploadFingerprint(UUID space,UUID document,String title,String fileName,String mime,String sha256){}
    private Upload persistUpload(Actor a,UUID space,UUID doc,String title,UploadStorage.Validated valid) {
        UUID category=null;
        if(doc!=null){var d=repo.document(a,doc,false);title=d.title();category=d.categoryId();}
        return persistUpload(a,space,doc,title,category,valid);
    }
    Upload persistUpload(Actor a,UUID space,UUID doc,String title,UUID category,UploadStorage.Validated valid) {
        return persistUpload(a,space,doc,title,category,valid,null,null);
    }
    Upload persistUpload(Actor a,UUID space,UUID doc,String title,UUID category,UploadStorage.Validated valid,JsonNode richContent,String richFingerprint) {
        if(doc!=null) {
            var existing=jdbc.sql("SELECT document_id,id,job_id FROM document_version WHERE document_id=:doc AND sha256=:hash AND authored_title=:title AND authored_category_id IS NOT DISTINCT FROM CAST(:category AS uuid) AND rich_fingerprint IS NOT DISTINCT FROM CAST(:fingerprint AS char(64)) AND rich_content IS NOT DISTINCT FROM CAST(:rich AS jsonb) AND review_state<>'REJECTED' ORDER BY version_no DESC LIMIT 1").param("doc",doc).param("hash",valid.sha256()).param("title",title).param("category",category).param("fingerprint",richFingerprint).param("rich",richContent==null?null:richContent.toString())
                .query((r,n)->new Upload(r.getObject("document_id",UUID.class),r.getObject("id",UUID.class),r.getObject("job_id",UUID.class))).optional();
            if(existing.isPresent()) {
                var reused=existing.get();
                int changed=jdbc.sql("UPDATE knowledge_document SET latest_version_id=:version WHERE id=:doc AND latest_version_id IS DISTINCT FROM :version")
                    .param("version",reused.versionId()).param("doc",doc).update();
                if(changed==1)audit.record(a,"KNOWLEDGE_VERSION_REUSED","KNOWLEDGE_DOCUMENT",doc,Map.of("versionId",reused.versionId().toString()));
                return reused;
            }
        } else {
            if(title==null||title.isBlank()||title.length()>240)throw new ApiException(400,"INVALID_DOCUMENT_TITLE","标题需要1至240字符");
            doc=UUID.randomUUID(); jdbc.sql("INSERT INTO knowledge_document(id,space_id,title,created_by) VALUES(:id,:space,:title,:actor)")
                .param("id",doc).param("space",space).param("title",title.trim()).param("actor",a.id()).update();
        }
        String key=storage.save(valid); UUID version=UUID.randomUUID();
        int number=jdbc.sql("SELECT COALESCE(max(version_no),0)+1 FROM document_version WHERE document_id=:doc").param("doc",doc).query(Integer.class).single();
        jdbc.sql("INSERT INTO document_version(id,document_id,version_no,file_name,mime_type,sha256,storage_key,file_size,created_by,authored_title,authored_category_id,rich_content,rich_fingerprint) VALUES(:id,:doc,:number,:name,:mime,:hash,:key,:size,:actor,:title,:category,CAST(:rich AS jsonb),:fingerprint)")
            .param("id",version).param("doc",doc).param("number",number).param("name",valid.name()).param("mime",valid.mime()).param("hash",valid.sha256()).param("key",key).param("size",valid.bytes().length).param("actor",a.id()).param("title",title).param("category",category).param("rich",richContent==null?null:richContent.toString()).param("fingerprint",richFingerprint).update();
        jdbc.sql("UPDATE knowledge_document SET latest_version_id=:v WHERE id=:doc").param("v",version).param("doc",doc).update();
        UUID job=startJob(a,version);
        audit.record(a,"KNOWLEDGE_UPLOADED","KNOWLEDGE_DOCUMENT",doc,Map.of("versionId",version.toString(),"versionNo",number,"jobId",job.toString()));
        return new Upload(doc,version,job);
    }
    private UUID startJob(Actor a,UUID version) {
        UUID job=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_job(id,requested_by,job_type,subject_type,subject_id,idempotency_key) VALUES(:id,:actor,'DOCUMENT_INGEST','DOCUMENT_VERSION',:version,:key)")
            .param("id",job).param("actor",a.id()).param("version",version).param("key","ingest:"+version+":"+job).update();
        jdbc.sql("INSERT INTO outbox_event(id,job_id,event_type,aggregate_type,aggregate_id) VALUES(:id,:job,'DOCUMENT_INGEST','DOCUMENT_VERSION',:version)")
            .param("id",UUID.randomUUID()).param("job",job).param("version",version).update();
        jdbc.sql("UPDATE document_version SET job_id=:job,ingest_state='QUEUED',error_code=NULL WHERE id=:v").param("job",job).param("v",version).update();
        return job;
    }
    private Version version(Actor a,UUID doc,UUID id) { return repo.versions(a,doc).stream().filter(v->v.id().equals(id)).findFirst().orElseThrow(KnowledgeRepository::notFound); }
    @Transactional public Upload retry(Actor a,UUID doc,UUID id) {
        editable(a,doc); var v=version(a,doc,id);
        if(Set.of("QUEUED","EXTRACTING","CHUNKING").contains(v.ingestState()))return new Upload(doc,id,v.jobId());
        if(!v.ingestState().equals("FAILED"))throw new ApiException(409,"VERSION_NOT_FAILED","只有失败版本可以重试");
        UUID job=startJob(a,id); audit.record(a,"KNOWLEDGE_RETRIED","KNOWLEDGE_DOCUMENT",doc,Map.of("versionId",id.toString(),"previousJobId",v.jobId().toString(),"jobId",job.toString()));
        return new Upload(doc,id,job);
    }
    @Transactional public Detail retryIndex(Actor a,UUID doc,UUID id) {
        editable(a,doc);var v=version(a,doc,id);
        if(!v.ingestState().equals("READY")||!"FAILED".equals(v.embeddingState()))
            throw new ApiException(409,"INDEX_NOT_FAILED","只有解析就绪且索引失败的版本可以重试索引");
        int changed=jdbc.sql("""
            UPDATE document_version SET embedding_state='PENDING',embedding_profile=NULL,
              embedding_attempts=0,embedding_error_code=NULL,embedding_lease=NULL,
              embedding_locked_until=NULL,embedding_available_at=now()
            WHERE id=:id AND document_id=:doc AND ingest_state='READY' AND embedding_state='FAILED'
              AND (embedding_locked_until IS NULL OR embedding_locked_until<=now())
            """).param("id",id).param("doc",doc).update();
        if(changed!=1)throw new ApiException(409,"INDEX_STATE_CONFLICT","索引状态已变化，请刷新后重试");
        audit.record(a,"KNOWLEDGE_INDEX_RETRIED","KNOWLEDGE_DOCUMENT",doc,
            Map.of("versionId",id.toString(),"previousErrorCode",Objects.toString(v.embeddingErrorCode(),"UNKNOWN")));
        return detail(a,repo.document(a,doc,false));
    }
    @Transactional public Detail publish(Actor a,UUID doc,UUID id) {
        var d=editable(a,doc); var v=version(a,doc,id);
        if(!v.ingestState().equals("READY")||v.chunkCount()<1)throw new ApiException(409,"VERSION_NOT_READY","版本解析并分块成功后才能发布");
        if(!v.reviewState().equals("APPROVED"))throw new ApiException(409,"VERSION_NOT_APPROVED","版本须经另一位知识编辑者审核通过后发布");
        if(id.equals(d.publishedVersionId()))return detail(a,d);
        jdbc.sql("UPDATE document_version SET publication_state='SUPERSEDED' WHERE document_id=:doc AND publication_state='PUBLISHED'").param("doc",doc).update();
        jdbc.sql("UPDATE document_version SET publication_state='PUBLISHED',published_at=now() WHERE id=:id").param("id",id).update();
        jdbc.sql("UPDATE knowledge_document d SET published_version_id=:v,publication_state='PUBLISHED',title=v.authored_title,category_id=v.authored_category_id FROM document_version v WHERE d.id=:doc AND v.id=:v").param("v",id).param("doc",doc).update();
        audit.record(a,"KNOWLEDGE_PUBLISHED","KNOWLEDGE_DOCUMENT",doc,Map.of("versionId",id.toString())); return detail(a,repo.document(a,doc,false));
    }
    @Transactional public Detail requestReview(Actor a,UUID doc,UUID id) {
        var d=editable(a,doc);var v=version(a,doc,id);
        if(!id.equals(d.latestVersionId())||!v.ingestState().equals("READY")||v.chunkCount()<1)throw new ApiException(409,"VERSION_NOT_READY","仅当前解析成功的版本可提交审核");
        if(v.reviewState().equals("PENDING"))return detail(a,d);
        if(!v.reviewState().equals("DRAFT"))throw new ApiException(409,"REVIEW_STATE_CONFLICT","此版本不能再次提交审核，请创建新版本");
        jdbc.sql("UPDATE document_version SET review_state='PENDING' WHERE id=:id AND review_state='DRAFT'").param("id",id).update();
        audit.record(a,"KNOWLEDGE_REVIEW_REQUESTED","KNOWLEDGE_DOCUMENT",doc,Map.of("versionId",id.toString()));return detail(a,repo.document(a,doc,false));
    }
    @Transactional public Detail review(Actor a,UUID doc,UUID id,String decision,String reason) {
        var d=editable(a,doc);var v=version(a,doc,id);
        boolean explicit=jdbc.sql("SELECT EXISTS(SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=:u AND r.code='KB_EDITOR')").param("u",a.id()).query(Boolean.class).single();
        if(!explicit||a.id().equals(v.authorId()))throw new ApiException(403,"INDEPENDENT_REVIEW_REQUIRED","须由另一位有空间编辑权限的知识编辑者审核");
        if(!Set.of("APPROVED","REJECTED").contains(Objects.requireNonNullElse(decision,""))||reason!=null&&reason.length()>500||"REJECTED".equals(decision)&&(reason==null||reason.isBlank()))throw new ApiException(400,"INVALID_REVIEW","审核决定无效，驳回需要1至500字符理由");
        if(!id.equals(d.latestVersionId())||!v.ingestState().equals("READY")||v.chunkCount()<1||!v.reviewState().equals("PENDING"))throw new ApiException(409,"REVIEW_STATE_CONFLICT","此版本当前不可审核");
        jdbc.sql("UPDATE document_version SET review_state=:decision,reviewer_id=:actor,review_reason=:reason,reviewed_at=now() WHERE id=:id AND review_state='PENDING'")
            .param("decision",decision).param("actor",a.id()).param("reason",reason==null||reason.isBlank()?null:reason.trim()).param("id",id).update();
        audit.record(a,"KNOWLEDGE_REVIEWED","KNOWLEDGE_DOCUMENT",doc,Map.of("versionId",id.toString(),"decision",decision));return detail(a,repo.document(a,doc,false));
    }
    @Transactional public Detail disable(Actor a,UUID doc) {
        var d=editable(a,doc); if(d.publicationState().equals("DISABLED"))return detail(a,d);
        withdraw(doc,"DISABLED"); audit.record(a,"KNOWLEDGE_DISABLED","KNOWLEDGE_DOCUMENT",doc,Map.of()); return detail(a,repo.document(a,doc,false));
    }
    private void withdraw(UUID doc,String state) {
        jdbc.sql("UPDATE document_version SET publication_state='SUPERSEDED' WHERE document_id=:doc AND publication_state='PUBLISHED'").param("doc",doc).update();
        jdbc.sql("UPDATE knowledge_document SET publication_state=:state,published_version_id=NULL WHERE id=:doc").param("doc",doc).param("state",state).update();
    }
    @Transactional public void archive(Actor a,UUID doc) {
        editable(a,doc); withdraw(doc,"ARCHIVED"); audit.record(a,"KNOWLEDGE_ARCHIVED","KNOWLEDGE_DOCUMENT",doc,Map.of());
    }
}


