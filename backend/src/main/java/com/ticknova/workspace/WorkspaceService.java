package com.ticknova.workspace;

import com.ticknova.shared.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.ticknova.workspace.WorkspaceDtos.*;

@Service
public class WorkspaceService {
    private final JdbcClient jdbc;
    public WorkspaceService(JdbcClient jdbc){this.jdbc=jdbc;}
    // Materialize the authorized publication IDs before matching content, choosing snippets or counting views.
    private static final String VISIBLE="""
        WITH visible AS MATERIALIZED (
          SELECT d.id,d.space_id,d.title,d.category_id,d.created_at,v.id AS version_id,v.published_at
          FROM knowledge_document d JOIN knowledge_space s ON s.id=d.space_id
          JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user AND m.can_read
          JOIN app_user u ON u.id=m.user_id AND u.active AND NOT u.must_change_password
          JOIN document_version v ON v.id=d.published_version_id AND v.document_id=d.id
          WHERE s.active AND d.publication_state='PUBLISHED' AND v.publication_state='PUBLISHED' AND v.ingest_state='READY' AND v.review_state='APPROVED'
        )
        """;
    private static final String HIT_SELECT="""
        SELECT d.id,d.space_id,d.title,d.category_id,
          COALESCE((SELECT left(c.content,240) FROM knowledge_chunk c WHERE c.document_version_id=d.version_id
            ORDER BY CASE WHEN c.content ILIKE :pattern ESCAPE '\\' THEN 0 ELSE 1 END,c.chunk_index,c.id LIMIT 1),'') AS snippet,
          (SELECT count(*) FROM knowledge_document_view w WHERE w.document_id=d.id) AS view_count
        FROM visible d
        """;
    private static final org.springframework.jdbc.core.RowMapper<KnowledgeHit> HIT=(r,n)->new KnowledgeHit(r.getObject("id",UUID.class),r.getObject("space_id",UUID.class),r.getString("title"),r.getString("snippet"),r.getObject("category_id",UUID.class),r.getLong("view_count"));

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Portal portal(Actor supplied,String input){
        Actor actor=current(supplied);String query=input==null?"":input.trim();
        if(input!=null&&input.length()>256)throw new ApiException(400,"INVALID_QUERY","搜索文字最多256字符");
        String pattern=pattern(query);
        List<KnowledgeHit> knowledge=query.isEmpty()?List.of():jdbc.sql(VISIBLE+HIT_SELECT+"""
             WHERE d.title ILIKE :pattern ESCAPE '\\' OR EXISTS
               (SELECT 1 FROM knowledge_chunk c WHERE c.document_version_id=d.version_id AND c.content ILIKE :pattern ESCAPE '\\')
             ORDER BY d.published_at DESC NULLS LAST,d.created_at DESC,d.id LIMIT 10
            """).param("user",actor.id()).param("pattern",pattern).query(HIT).list();
        List<KnowledgeHit> popular=jdbc.sql(VISIBLE+HIT_SELECT+" ORDER BY view_count DESC,d.published_at DESC NULLS LAST,d.created_at DESC,d.id LIMIT 6")
            .param("user",actor.id()).param("pattern",pattern).query(HIT).list();
        var tickets=jdbc.sql("""
            SELECT t.id,t.ticket_no,t.title,t.status,t.priority,t.updated_at
            FROM ticket t JOIN app_user u ON u.id=:user AND u.active AND NOT u.must_change_password
            WHERE ((:own AND t.requester_id=:user) OR (:agent AND EXISTS
              (SELECT 1 FROM queue_member m JOIN support_queue q ON q.id=m.queue_id
                WHERE m.queue_id=t.queue_id AND m.user_id=:user AND q.active)))
              AND (:empty OR t.title ILIKE :pattern ESCAPE '\\' OR t.description ILIKE :pattern ESCAPE '\\' OR CAST(t.ticket_no AS text)=:query)
            ORDER BY t.updated_at DESC,t.id LIMIT 10
            """).param("user",actor.id()).param("own",actor.hasRole("REQUESTER")||actor.hasRole("AGENT")).param("agent",actor.hasRole("AGENT"))
            .param("empty",query.isEmpty()).param("pattern",pattern).param("query",query)
            .query((r,n)->new TicketHit(r.getObject("id",UUID.class),r.getLong("ticket_no"),r.getString("title"),r.getString("status"),r.getString("priority"),r.getTimestamp("updated_at").toInstant())).list();
        var categories=jdbc.sql("""
            SELECT c.id,c.space_id,c.parent_id,c.name,s.name AS space_name
            FROM knowledge_category c JOIN knowledge_space s ON s.id=c.space_id AND s.active
            JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user AND m.can_read
            JOIN app_user u ON u.id=m.user_id AND u.active AND NOT u.must_change_password
            ORDER BY s.name,s.id,c.name,c.id
            """).param("user",actor.id()).query((r,n)->new PortalCategory(r.getObject("id",UUID.class),r.getObject("space_id",UUID.class),r.getObject("parent_id",UUID.class),r.getString("name"),r.getString("space_name"))).list();
        return new Portal(query,knowledge,tickets,popular,categories);
    }

    @Transactional
    public void view(Actor supplied,UUID document){
        // Match the lock order used by knowledge mutations and keep authorization valid through the insert.
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR SHARE").param("id",supplied.id()).query(UUID.class).list();
        current(supplied);
        jdbc.sql("SELECT s.id FROM knowledge_space s JOIN knowledge_document d ON d.space_id=s.id WHERE d.id=:id FOR SHARE OF s")
            .param("id",document).query(UUID.class).list();
        var authorized=jdbc.sql("""
            SELECT d.id FROM knowledge_document d JOIN knowledge_space s ON s.id=d.space_id
            JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:user AND m.can_read
            JOIN app_user u ON u.id=m.user_id AND u.active AND NOT u.must_change_password
            JOIN document_version v ON v.id=d.published_version_id AND v.document_id=d.id
            WHERE d.id=:id AND s.active AND d.publication_state='PUBLISHED'
              AND v.publication_state='PUBLISHED' AND v.ingest_state='READY' AND v.review_state='APPROVED' FOR SHARE OF d,v
            """).param("id",document).param("user",supplied.id()).query(UUID.class).optional();
        if(authorized.isEmpty())throw new ApiException(404,"KNOWLEDGE_NOT_FOUND","知识资源不存在或无权访问");
        jdbc.sql("INSERT INTO knowledge_document_view(document_id,user_id,view_date) VALUES(:id,:user,(now() AT TIME ZONE 'UTC')::date) ON CONFLICT DO NOTHING")
            .param("id",document).param("user",supplied.id()).update();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Analytics analytics(Actor supplied,String from,String to){
        current(supplied).requireRole("ADMIN");LocalDate start,end;OffsetDateTime lower,upper;
        try{
            if((from!=null&&!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))||(to!=null&&!to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")))throw new DateTimeException("format");
            end=to==null?LocalDate.now(ZoneOffset.UTC):LocalDate.parse(to);
            start=from==null?end.minusDays(29):LocalDate.parse(from);
            if(start.getYear()<1||end.getYear()<1||start.getYear()>9999||end.getYear()>9999)throw new DateTimeException("year");
            long days=ChronoUnit.DAYS.between(start,end);if(days<0||days>365)throw new DateTimeException("range");
            lower=start.atStartOfDay().atOffset(ZoneOffset.UTC);upper=end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        }catch(DateTimeException|ArithmeticException ex){throw new ApiException(400,"INVALID_ANALYTICS_RANGE","日期须为YYYY-MM-DD格式（0001至9999年），且范围包含1至366天");}
        var bounds=Map.<String,Object>of("lower",lower,"upper",upper);
        long total=jdbc.sql("SELECT count(*) FROM ticket WHERE created_at>=:lower AND created_at<:upper").params(bounds).query(Long.class).single();
        List<Count> statuses=counts("status",bounds),priorities=counts("priority",bounds);
        Map<LocalDate,Long> created=new HashMap<>(),resolved=new HashMap<>();
        jdbc.sql("SELECT (created_at AT TIME ZONE 'UTC')::date AS day,count(*) AS count FROM ticket WHERE created_at>=:lower AND created_at<:upper GROUP BY day")
            .params(bounds).query((r,n)->{created.put(r.getObject("day",LocalDate.class),r.getLong("count"));return true;}).list();
        jdbc.sql("SELECT (created_at AT TIME ZONE 'UTC')::date AS day,count(DISTINCT ticket_id) AS count FROM ticket_transition WHERE to_status='RESOLVED' AND created_at>=:lower AND created_at<:upper GROUP BY day")
            .params(bounds).query((r,n)->{resolved.put(r.getObject("day",LocalDate.class),r.getLong("count"));return true;}).list();
        List<Daily> daily=new ArrayList<>();for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1))daily.add(new Daily(date,created.getOrDefault(date,0L),resolved.getOrDefault(date,0L)));
        long published=jdbc.sql("""
            SELECT count(*) FROM knowledge_document d JOIN knowledge_space s ON s.id=d.space_id AND s.active
            JOIN document_version v ON v.id=d.published_version_id AND v.document_id=d.id
            WHERE d.publication_state='PUBLISHED' AND v.publication_state='PUBLISHED' AND v.ingest_state='READY' AND v.review_state='APPROVED'
            """).query(Long.class).single();
        long pending=jdbc.sql("""
            SELECT count(*) FROM knowledge_document d JOIN knowledge_space s ON s.id=d.space_id AND s.active
            JOIN document_version v ON v.id=d.latest_version_id AND v.document_id=d.id
            WHERE d.publication_state<>'ARCHIVED' AND v.review_state='PENDING' AND v.ingest_state='READY'
            """).query(Long.class).single();
        long completed=jdbc.sql("SELECT count(*) FROM ai_suggestion WHERE created_at>=:lower AND created_at<:upper").params(bounds).query(Long.class).single();
        long refused=jdbc.sql("SELECT count(*) FROM ai_suggestion WHERE created_at>=:lower AND created_at<:upper AND result_json->>'outcome'='INSUFFICIENT_EVIDENCE'").params(bounds).query(Long.class).single();
        long accepted=feedback("ACCEPTED",bounds),rejected=feedback("REJECTED",bounds);
        return new Analytics(start,end,total,statuses,priorities,daily,new KnowledgeCounts(published,pending),new AiCounts(completed,refused,accepted,rejected));
    }
    private List<Count> counts(String column,Map<String,Object> bounds){
        // Only the two internal constant column names above are accepted.
        if(!Set.of("status","priority").contains(column))throw new IllegalArgumentException("column");
        return jdbc.sql("SELECT "+column+" AS key,count(*) AS count FROM ticket WHERE created_at>=:lower AND created_at<:upper GROUP BY "+column+" ORDER BY "+column)
            .params(bounds).query((r,n)->new Count(r.getString("key"),r.getLong("count"))).list();
    }
    private long feedback(String value,Map<String,Object> bounds){return jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_FEEDBACK_RECORDED' AND metadata->>'feedback'=:feedback AND created_at>=:lower AND created_at<:upper").params(bounds).param("feedback",value).query(Long.class).single();}
    private Actor current(Actor supplied){
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id AND active AND NOT must_change_password)").param("id",supplied.id()).query(Boolean.class).single())throw new ApiException(401,"UNAUTHENTICATED","请重新登录");
        Set<String> roles=Set.copyOf(jdbc.sql("SELECT r.code FROM app_role r JOIN user_role u ON u.role_id=r.id WHERE u.user_id=:id").param("id",supplied.id()).query(String.class).list());
        return new Actor(supplied.id(),supplied.username(),supplied.displayName(),roles,false);
    }
    private static String pattern(String text){return "%"+text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
}



