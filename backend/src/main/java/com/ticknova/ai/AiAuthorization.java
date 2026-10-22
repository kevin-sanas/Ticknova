package com.ticknova.ai;

import com.ticknova.shared.*;
import com.ticknova.ticket.*;
import com.ticknova.ai.model.AiModel;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Checks the complete original request scope, including requests with no citations. */
@Component
public class AiAuthorization {
    private final JdbcClient jdbc;
    private final TicketRepository tickets;
    private final com.ticknova.catalog.CatalogService catalog;
    public AiAuthorization(JdbcClient jdbc,TicketRepository tickets,com.ticknova.catalog.CatalogService catalog) {this.jdbc=jdbc;this.tickets=tickets;this.catalog=catalog;}
    public Actor actor(UUID id) {
        var valid=jdbc.sql("SELECT username,display_name FROM app_user WHERE id=:id AND active AND NOT must_change_password").param("id",id)
            .query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional().orElseThrow(AiAuthorization::unavailable);
        Set<String> roles=new HashSet<>(jdbc.sql("SELECT r.code FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=:id").param("id",id).query(String.class).list());
        return new Actor(id,valid[0],valid[1],roles,false);
    }
    public List<UUID> resolve(Actor actor,List<UUID> selected) {
        actor(actor.id());
        if(selected==null||selected.stream().anyMatch(Objects::isNull)||new HashSet<>(selected).size()!=selected.size()||selected.size()>10)throw new ApiException(400,"INVALID_AI_SPACES","请选择最多10个不同空间");
        List<UUID> ids=selected.isEmpty()?jdbc.sql("SELECT s.id FROM knowledge_space s JOIN knowledge_space_member m ON m.space_id=s.id WHERE m.user_id=:u AND m.can_read AND s.active ORDER BY s.id").param("u",actor.id()).query(UUID.class).list():selected.stream().sorted().toList();
        scopes(actor,ids);return List.copyOf(ids);
    }
    public void scopes(Actor actor,List<UUID> ids) {
        actor(actor.id());if(ids.isEmpty())return;
        long count=jdbc.sql("SELECT count(*) FROM knowledge_space s JOIN knowledge_space_member m ON m.space_id=s.id WHERE s.id IN (:ids) AND s.active AND m.user_id=:u AND m.can_read").param("ids",ids).param("u",actor.id()).query(Long.class).single();
        if(count!=ids.size())throw unavailable();
    }
    public TicketRepository.Row ticket(Actor a,UUID id) {
        var row=tickets.visible(a,id);var t=row.summary();
        boolean own=a.id().equals(t.requesterId())&&(a.hasRole("REQUESTER")||a.hasRole("AGENT"));
        if(!own&&!catalog.canProcessQueue(a,t.queueId()))throw unavailable();return row;
    }
    public List<String> publicReplies(UUID id) {
        // SQL never selects internal notes. The total prompt budget is enforced before generation.
        return jdbc.sql("SELECT body FROM ticket_comment WHERE ticket_id=:id AND visibility='PUBLIC_REPLY' ORDER BY created_at,id LIMIT 100").param("id",id).query(String.class).list();
    }
    public List<AiModel.ServiceOption> services() {
        return jdbc.sql("SELECT id,name FROM service_catalog WHERE active ORDER BY id LIMIT 200").query((r,n)->new AiModel.ServiceOption(r.getObject(1,UUID.class),r.getString(2))).list();
    }
    public void citation(Actor actor,UUID chunk,List<UUID> scopes) {
        if(scopes.isEmpty())throw unavailable();
        boolean exists=jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM knowledge_chunk c JOIN document_version v ON v.id=c.document_version_id
              JOIN knowledge_document d ON d.id=v.document_id JOIN knowledge_space s ON s.id=d.space_id
              JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:u
              WHERE c.id=:chunk AND d.space_id IN (:spaces) AND s.active AND m.can_read AND v.ingest_state='READY'
              AND d.publication_state='PUBLISHED' AND v.publication_state='PUBLISHED' AND v.review_state='APPROVED' AND d.published_version_id=v.id)
            """).param("u",actor.id()).param("chunk",chunk).param("spaces",scopes).query(Boolean.class).single();
        if(!exists)throw unavailable();
    }
    public static ApiException unavailable() {return new ApiException(404,"SOURCE_UNAVAILABLE","AI任务或来源不存在或已不可用");}
}

