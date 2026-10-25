package com.ticknova.knowledge;

import com.ticknova.shared.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static com.ticknova.knowledge.KnowledgeDtos.*;

@Repository
public class KnowledgeRepository {
    final JdbcClient jdbc;
    final KnowledgePolicy policy;
    public KnowledgeRepository(JdbcClient jdbc,KnowledgePolicy policy){this.jdbc=jdbc;this.policy=policy;}
    private static final String ACTIVE=" EXISTS(SELECT 1 FROM app_user au WHERE au.id=:user AND au.active) ";
    private static final String ADMIN=" EXISTS(SELECT 1 FROM user_role ar JOIN app_role role ON role.id=ar.role_id WHERE ar.user_id=:user AND role.code='ADMIN') ";
    private static final String EDITOR=" EXISTS(SELECT 1 FROM user_role er JOIN app_role role ON role.id=er.role_id WHERE er.user_id=:user AND role.code='KB_EDITOR') ";
    private static final String REVIEWER=" EXISTS(SELECT 1 FROM user_role rr JOIN app_role role ON role.id=rr.role_id WHERE rr.user_id=:user AND role.code='KB_REVIEWER') ";
    static final String EDIT=" (m.can_read AND m.can_edit AND NOT m.can_review AND "+EDITOR+") ";
    static final String REVIEW=" (m.can_read AND m.can_review AND NOT m.can_edit AND "+REVIEWER+") ";
    static final String PENDING=" ("+REVIEW+" AND d.latest_version_id=v.id AND v.ingest_state='READY' AND v.review_state='PENDING' AND v.created_by<>:user AND EXISTS(SELECT 1 FROM knowledge_chunk rc WHERE rc.document_version_id=v.id)) ";
    private static final String SPACE="""
        SELECT s.*,COALESCE(m.can_read,false) AS can_read,COALESCE(m.can_edit,false) AS can_edit,COALESCE(m.can_review,false) AS can_review,%s AS editor_role,%s AS admin_role,%s AS reviewer_role
        FROM knowledge_space s LEFT JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user
        """.formatted(EDITOR,ADMIN,REVIEWER);
    public List<Space> spaces(Actor a){return jdbc.sql(SPACE+" WHERE s.active AND "+ACTIVE+" AND (m.can_read OR "+ADMIN+") ORDER BY s.created_at,s.id").param("user",a.id()).query((r,n)->space(r,a)).list();}
    public Space space(Actor a,UUID id,boolean lock){return jdbc.sql(SPACE+" WHERE s.id=:id AND s.active AND "+ACTIVE+" AND (m.can_read OR "+ADMIN+")"+(lock?" FOR UPDATE OF s":"")).param("id",id).param("user",a.id()).query((r,n)->space(r,a)).optional().orElseThrow(KnowledgeRepository::notFound);}
    private Space space(ResultSet r,Actor a)throws SQLException{return new Space(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("active"),r.getBoolean("can_read"),r.getBoolean("editor_role")&&r.getBoolean("can_read")&&r.getBoolean("can_edit")&&!r.getBoolean("can_review"),r.getBoolean("admin_role"),r.getBoolean("reviewer_role")&&r.getBoolean("can_read")&&r.getBoolean("can_review")&&!r.getBoolean("can_edit"));}
    static final String DOCUMENT="""
        SELECT d.*,CASE WHEN %s THEN v.ingest_state ELSE pv.ingest_state END AS latest_ingest_state,
          CASE WHEN %s THEN d.latest_version_id ELSE d.published_version_id END AS visible_latest,
          CASE WHEN %s THEN (SELECT count(*) FROM document_version dv WHERE dv.document_id=d.id) ELSE CASE WHEN %s AND d.published_version_id IS NOT NULL AND d.published_version_id<>v.id THEN 2 ELSE 1 END END AS version_count,
          %s AS effective_edit,CASE WHEN %s THEN v.authored_title ELSE d.title END AS visible_title
        FROM knowledge_document d JOIN knowledge_space s ON s.id=d.space_id
        JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user
        LEFT JOIN document_version v ON v.id=d.latest_version_id LEFT JOIN document_version pv ON pv.id=d.published_version_id
        WHERE s.active AND m.can_read AND d.publication_state<>'ARCHIVED' AND %s
          AND (%s OR (d.publication_state='PUBLISHED' AND pv.ingest_state='READY' AND pv.review_state='APPROVED'))
        """.formatted("("+EDIT+" OR "+PENDING+")","("+EDIT+" OR "+PENDING+")",EDIT,PENDING,EDIT,PENDING,ACTIVE,"("+EDIT+" OR "+PENDING+")");
    public Summary document(Actor a,UUID id,boolean lock){return jdbc.sql(DOCUMENT+" AND d.id=:id"+(lock?" FOR UPDATE OF d":"")).param("user",a.id()).param("id",id).query((r,n)->summary(r,a)).optional().orElseThrow(KnowledgeRepository::notFound);}
    public PageResult<Summary> documents(Actor a,UUID space,int page,int size,String q){return documents(a,space,page,size,q,null);}
    public PageResult<Summary> documents(Actor a,UUID space,int page,int size,String q,UUID category){
        PageResult.validate(page,size);String query=q==null?"":q.trim();if(query.length()>240)throw new ApiException(400,"INVALID_QUERY","搜索文字过长");
        String filter=" AND d.space_id=:space AND d.title ILIKE :q";
        if(category!=null)filter+=" AND d.category_id IN (WITH RECURSIVE subtree AS (SELECT id FROM knowledge_category WHERE id=:category AND space_id=:space UNION ALL SELECT c.id FROM knowledge_category c JOIN subtree t ON c.parent_id=t.id WHERE c.space_id=:space) SELECT id FROM subtree)";
        var countSpec=jdbc.sql("SELECT count(*) FROM ("+DOCUMENT+filter+") scoped").param("user",a.id()).param("space",space).param("q","%"+query+"%");if(category!=null)countSpec.param("category",category);
        long count=countSpec.query(Long.class).single();
        var listSpec=jdbc.sql(DOCUMENT+filter+" ORDER BY d.created_at DESC,d.id LIMIT :limit OFFSET :offset").param("user",a.id()).param("space",space).param("q","%"+query+"%").param("limit",size).param("offset",(long)page*size);if(category!=null)listSpec.param("category",category);
        return new PageResult<>(listSpec.query((r,n)->summary(r,a)).list(),count,page,size);
    }
    public PageResult<ReviewTask> reviewTasks(Actor a,int page,int size){
        PageResult.validate(page,size);
        boolean currentEditor=a.hasRole("KB_REVIEWER")&&jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role role ON role.id=ur.role_id WHERE u.id=:user AND u.active AND role.code='KB_REVIEWER')")
            .param("user",a.id()).query(Boolean.class).single();
        if(!currentEditor)throw new ApiException(403,"INDEPENDENT_REVIEW_REQUIRED","仅知识审核者可查看待我审核");
        String scope="""
            FROM document_version v JOIN knowledge_document d ON d.id=v.document_id
            JOIN knowledge_space s ON s.id=d.space_id JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user
            WHERE s.active AND m.can_read AND m.can_review AND NOT m.can_edit AND d.publication_state<>'ARCHIVED'
              AND d.latest_version_id=v.id AND v.ingest_state='READY' AND v.review_state='PENDING' AND v.created_by<>:user
              AND EXISTS(SELECT 1 FROM knowledge_chunk c WHERE c.document_version_id=v.id)
              AND %s AND %s
            """.formatted(ACTIVE,REVIEW);
        long count=jdbc.sql("SELECT count(*) "+scope).param("user",a.id()).query(Long.class).single();
        var rows=jdbc.sql("SELECT d.id AS document_id,d.space_id,v.authored_title AS title,v.id AS version_id,v.version_no,v.file_name,v.created_by,v.created_at "+scope+" ORDER BY v.created_at DESC,v.id LIMIT :limit OFFSET :offset")
            .param("user",a.id()).param("limit",size).param("offset",(long)page*size)
            .query((r,n)->new ReviewTask(r.getObject("document_id",UUID.class),r.getObject("space_id",UUID.class),r.getString("title"),r.getObject("version_id",UUID.class),r.getInt("version_no"),r.getString("file_name"),r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant())).list();
        return new PageResult<>(rows,count,page,size);
    }
    private Summary summary(ResultSet r,Actor a)throws SQLException{return new Summary(r.getObject("id",UUID.class),r.getObject("space_id",UUID.class),r.getString("visible_title"),r.getString("publication_state"),r.getObject("visible_latest",UUID.class),r.getObject("published_version_id",UUID.class),r.getString("latest_ingest_state"),r.getInt("version_count"),r.getTimestamp("created_at").toInstant(),r.getBoolean("effective_edit"),r.getObject("category_id",UUID.class));}
    public List<Version> versions(Actor a,UUID doc){
        return jdbc.sql("""
            SELECT v.*,j.status AS job_status,j.attempt_count,d.latest_version_id,d.published_version_id,
             (v.embedding_locked_until IS NULL OR v.embedding_locked_until<=now()) AS index_retry_available,%s AS effective_edit,
             %s AS effective_review,
             EXISTS(SELECT 1 FROM knowledge_space_member reviewer
               JOIN app_user reviewer_user ON reviewer_user.id=reviewer.user_id AND reviewer_user.active
               JOIN user_role reviewer_role ON reviewer_role.user_id=reviewer.user_id
               JOIN app_role reviewer_code ON reviewer_code.id=reviewer_role.role_id AND reviewer_code.code='KB_REVIEWER'
               WHERE reviewer.space_id=d.space_id AND reviewer.can_read AND reviewer.can_review AND NOT reviewer.can_edit AND reviewer.user_id<>v.created_by) AS has_eligible_reviewer,
             (SELECT count(*) FROM knowledge_chunk c WHERE c.document_version_id=v.id) AS chunk_count
            FROM document_version v JOIN knowledge_document d ON d.id=v.document_id JOIN knowledge_space s ON s.id=d.space_id
            JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user LEFT JOIN ai_job j ON j.id=v.job_id
            WHERE v.document_id=:id AND m.can_read AND s.active AND d.publication_state<>'ARCHIVED' AND %s
             AND (%s OR %s OR (d.publication_state='PUBLISHED' AND d.published_version_id=v.id AND v.ingest_state='READY' AND v.review_state='APPROVED')) ORDER BY v.version_no DESC
            """.formatted(EDIT,REVIEW,ACTIVE,EDIT,PENDING)).param("id",doc).param("user",a.id()).query((r,n)->{
                boolean edit=r.getBoolean("effective_edit"),ready=r.getString("ingest_state").equals("READY")&&r.getInt("chunk_count")>0,current=r.getObject("id",UUID.class).equals(r.getObject("latest_version_id",UUID.class));
                String state=r.getString("review_state");UUID author=r.getObject("created_by",UUID.class);Timestamp reviewed=r.getTimestamp("reviewed_at");
                String embeddingState=r.getString("embedding_state");
                boolean request=edit&&ready&&current&&state.equals("DRAFT"),review=r.getBoolean("effective_review")&&ready&&current&&state.equals("PENDING")&&!a.id().equals(author),publish=edit&&ready&&state.equals("APPROVED")&&!r.getObject("id",UUID.class).equals(r.getObject("published_version_id",UUID.class)),retryIndex=edit&&r.getString("ingest_state").equals("READY")&&embeddingState.equals("FAILED")&&r.getBoolean("index_retry_available");
                var actions=new LinkedHashSet<String>();
                if(request)actions.add("SUBMIT_REVIEW");if(review)actions.addAll(Set.of("APPROVE","REJECT"));if(publish)actions.add("PUBLISH");
                if(edit&&r.getString("ingest_state").equals("FAILED"))actions.add("RETRY_PARSE");if(retryIndex)actions.add("RETRY_INDEX");
                return new Version(r.getObject("id",UUID.class),r.getInt("version_no"),r.getString("file_name"),r.getString("mime_type"),r.getString("sha256"),r.getString("ingest_state"),r.getString("publication_state"),r.getString("error_code"),r.getTimestamp("created_at").toInstant(),edit?r.getObject("job_id",UUID.class):null,edit?r.getString("job_status"):null,edit?r.getInt("attempt_count"):0,r.getInt("chunk_count"),state,author,r.getObject("reviewer_id",UUID.class),r.getString("review_reason"),reviewed==null?null:reviewed.toInstant(),request,review,publish,edit&&r.getBoolean("has_eligible_reviewer"),edit?embeddingState:null,edit?r.getString("embedding_error_code"):null,retryIndex,Set.copyOf(actions));
            }).list();
    }
    public Job job(Actor a,UUID id){return jdbc.sql("""
        SELECT j.id,j.status,j.error_code,j.attempt_count,v.id AS version_id,d.id AS document_id FROM ai_job j
        JOIN document_version v ON j.subject_type='DOCUMENT_VERSION' AND j.subject_id=v.id JOIN knowledge_document d ON d.id=v.document_id
        JOIN knowledge_space s ON s.id=d.space_id JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user
        WHERE j.id=:id AND s.active AND m.can_read AND d.publication_state<>'ARCHIVED' AND %s AND %s
        """.formatted(ACTIVE,EDIT)).param("id",id).param("user",a.id()).query((r,n)->new Job(r.getObject("id",UUID.class),r.getString("status"),r.getString("error_code"),r.getInt("attempt_count"),r.getObject("version_id",UUID.class),r.getObject("document_id",UUID.class))).optional().orElseThrow(KnowledgeRepository::notFound);}
    static ApiException notFound(){return new ApiException(404,"KNOWLEDGE_NOT_FOUND","知识资源不存在或无权访问");}
}
