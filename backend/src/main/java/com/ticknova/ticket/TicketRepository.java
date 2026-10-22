package com.ticknova.ticket;

import com.ticknova.shared.Actor;
import com.ticknova.shared.ApiException;
import com.ticknova.shared.PageResult;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.ticknova.ticket.TicketDtos.*;

@Repository
public class TicketRepository {
    private final JdbcClient jdbc;
    public TicketRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public record Row(Summary summary,String description,String resolutionNote) {}
    private static final String SELECT="""
        SELECT t.*,u.display_name requester_name,q.name queue_name,a.display_name assignee_name,s.name service_name,
        COALESCE((SELECT array_agg(l.label ORDER BY l.label) FROM ticket_label l WHERE l.ticket_id=t.id),ARRAY[]::varchar[]) tags
        FROM ticket t JOIN app_user u ON u.id=t.requester_id JOIN support_queue q ON q.id=t.queue_id
        LEFT JOIN app_user a ON a.id=t.assignee_id LEFT JOIN service_catalog s ON s.id=t.service_id
        """;
    private static Instant instant(ResultSet r,String name) throws SQLException {return r.getTimestamp(name).toInstant();}
    private static final RowMapper<Row> MAPPER=(r,n)->new Row(new Summary(r.getObject("id",UUID.class),r.getLong("ticket_no"),r.getString("title"),
        r.getString("priority"),r.getString("status"),r.getObject("requester_id",UUID.class),r.getString("requester_name"),
        r.getObject("queue_id",UUID.class),r.getString("queue_name"),r.getObject("assignee_id",UUID.class),r.getString("assignee_name"),
        r.getObject("service_id",UUID.class),r.getString("service_name"),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at"),
        Arrays.asList((String[])r.getArray("tags").getArray())),r.getString("description"),r.getString("resolution_note"));
    public Row get(UUID id) {return jdbc.sql(SELECT+" WHERE t.id=:id").param("id",id).query(MAPPER).optional()
        .orElseThrow(()->new ApiException(404,"TICKET_NOT_FOUND","工单不存在"));}
    public Row visible(Actor actor,UUID id) {
        return jdbc.sql(SELECT+"""
            WHERE t.id=:id AND ((:own AND t.requester_id=:actor) OR (:agent AND EXISTS
            (SELECT 1 FROM queue_member qm JOIN support_queue sq ON sq.id=qm.queue_id
             WHERE qm.queue_id=t.queue_id AND qm.user_id=:actor AND sq.active)))
            """).param("id",id).param("actor",actor.id()).param("own",actor.hasRole("REQUESTER")||actor.hasRole("AGENT"))
            .param("agent",actor.hasRole("AGENT")).query(MAPPER).optional()
            .orElseThrow(()->new ApiException(403,"TICKET_FORBIDDEN","工单不存在或当前用户无权访问"));
    }

    public PageResult<Summary> list(Actor actor,int page,int size,String keyword,String status,String priority,UUID queueId,UUID assigneeId,UUID serviceId,String tag,String sort) {
        return list(actor,page,size,keyword,status,priority,queueId,assigneeId,serviceId,tag,sort,false);
    }
    public PageResult<Summary> list(Actor actor,int page,int size,String keyword,String status,String priority,UUID queueId,UUID assigneeId,UUID serviceId,String tag,String sort,boolean unassigned) {
        PageResult.validate(page,size);
        String order=switch(sort==null?"updatedAt,desc":sort) {
            case "updatedAt,desc" -> "t.updated_at DESC,t.id";
            case "createdAt,desc" -> "t.created_at DESC,t.id";
            case "ticketNo,desc" -> "t.ticket_no DESC";
            case "ticketNo,asc" -> "t.ticket_no ASC";
            default -> throw new ApiException(400,"INVALID_SORT","不支持此排序方式");
        };
        StringBuilder where=new StringBuilder(" WHERE ((:own AND t.requester_id=:actor) OR (:agent AND EXISTS (SELECT 1 FROM queue_member qm JOIN support_queue sq ON sq.id=qm.queue_id WHERE qm.queue_id=t.queue_id AND qm.user_id=:actor AND sq.active)))");
        Map<String,Object> params=new HashMap<>();params.put("own",actor.hasRole("REQUESTER")||actor.hasRole("AGENT"));params.put("actor",actor.id());params.put("agent",actor.hasRole("AGENT"));
        if(keyword!=null&&!keyword.isBlank()) {
            if(keyword.length()>200)throw new ApiException(400,"INVALID_SEARCH","搜索词最多200字");
            where.append(" AND (t.title ILIKE :keyword ESCAPE '\\' OR t.description ILIKE :keyword ESCAPE '\\' OR CAST(t.ticket_no AS text)=:number)");
            params.put("keyword","%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");params.put("number",keyword);
        }
        if(status!=null&&!status.isBlank()) {where.append(" AND t.status=:status");params.put("status",status);}
        if(priority!=null&&!priority.isBlank()){where.append(" AND t.priority=:priority");params.put("priority",priority);}
        if(queueId!=null){where.append(" AND t.queue_id=:queue");params.put("queue",queueId);}
        if(unassigned)where.append(" AND t.assignee_id IS NULL");
        if(assigneeId!=null){where.append(" AND t.assignee_id=:assignee");params.put("assignee",assigneeId);}
        if(serviceId!=null){where.append(" AND t.service_id=:service");params.put("service",serviceId);}
        if(tag!=null&&!tag.isBlank()){where.append(" AND EXISTS(SELECT 1 FROM ticket_label tl WHERE tl.ticket_id=t.id AND tl.label=:tag)");params.put("tag",tag);}
        long total=jdbc.sql("SELECT count(*) FROM ticket t"+where).params(params).query(Long.class).single();
        params.put("limit",size);params.put("offset",(long)page*size);
        var rows=jdbc.sql(SELECT+where+" ORDER BY "+order+" LIMIT :limit OFFSET :offset").params(params).query(MAPPER).list();
        return new PageResult<>(rows.stream().map(Row::summary).toList(),total,page,size);
    }
    public List<Comment> comments(UUID ticketId,String visibility) {
        return jdbc.sql("SELECT c.*,u.display_name author_name FROM ticket_comment c JOIN app_user u ON u.id=c.author_id WHERE ticket_id=:id AND visibility=:visibility ORDER BY c.created_at,c.id")
            .param("id",ticketId).param("visibility",visibility).query((r,n)->new Comment(r.getObject("id",UUID.class),ticketId,r.getObject("author_id",UUID.class),r.getString("author_name"),r.getString("visibility"),r.getString("body"),instant(r,"created_at"))).list();
    }
    public Comment comment(UUID id) {return jdbc.sql("SELECT c.*,u.display_name author_name FROM ticket_comment c JOIN app_user u ON u.id=c.author_id WHERE c.id=:id").param("id",id)
        .query((r,n)->new Comment(id,r.getObject("ticket_id",UUID.class),r.getObject("author_id",UUID.class),r.getString("author_name"),r.getString("visibility"),r.getString("body"),instant(r,"created_at"))).optional()
        .orElseThrow(()->new ApiException(404,"COMMENT_NOT_FOUND","回复不存在"));}
    public List<Transition> transitions(UUID ticketId) {return jdbc.sql("SELECT h.*,u.display_name actor_name FROM ticket_transition h JOIN app_user u ON u.id=h.actor_id WHERE ticket_id=:id ORDER BY h.created_at,h.id")
        .param("id",ticketId).query((r,n)->new Transition(r.getObject("id",UUID.class),r.getString("from_status"),r.getString("to_status"),r.getString("reason"),r.getString("actor_name"),instant(r,"created_at"))).list();}
}
