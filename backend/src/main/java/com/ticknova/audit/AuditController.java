package com.ticknova.audit;

import com.ticknova.shared.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public AuditController(JdbcClient jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @GetMapping public PageResult<AuditView> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
                                                @RequestParam(required=false) String action,
                                                @RequestParam(required=false) String actor,
                                                @RequestParam(required=false) Instant from,
                                                @RequestParam(required=false) Instant to) {
        CurrentUser.get().requireRole("ADMIN"); PageResult.validate(page,size);
        String normalized=action==null?"":action.trim();
        if(!normalized.isEmpty()&&!normalized.matches("[A-Z][A-Z0-9_]{0,99}"))throw new ApiException(400,"INVALID_AUDIT_ACTION","审计动作编码无效");
        if(from!=null&&to!=null&&!from.isBefore(to))throw new ApiException(400,"INVALID_AUDIT_RANGE","开始时间必须早于结束时间");
        UUID actorId=null;boolean systemActor=false;
        if(actor!=null&&!actor.isBlank()) {
            if("SYSTEM".equals(actor.trim()))systemActor=true;
            else try { actorId=UUID.fromString(actor.trim()); }
            catch(IllegalArgumentException ignored) { throw new ApiException(400,"INVALID_AUDIT_ACTOR","审计操作人无效"); }
        }
        StringBuilder filter=new StringBuilder(" WHERE 1=1");
        Map<String,Object> params=new HashMap<>();
        if(!normalized.isEmpty()){filter.append(" AND e.action=:action");params.put("action",normalized);}
        if(systemActor)filter.append(" AND e.actor_id IS NULL");
        else if(actorId!=null){filter.append(" AND e.actor_id=:actor");params.put("actor",actorId);}
        if(from!=null){filter.append(" AND e.created_at>=:from");params.put("from",java.sql.Timestamp.from(from));}
        if(to!=null){filter.append(" AND e.created_at<:to");params.put("to",java.sql.Timestamp.from(to));}
        long total=jdbc.sql("SELECT count(*) FROM audit_event e"+filter).params(params).query(Long.class).single();
        params.put("size",size);params.put("offset",(long)page*size);
        var items=jdbc.sql("""
            SELECT e.id,e.actor_id,u.display_name AS actor_name,e.action,e.subject_type,e.subject_id,e.trace_id,e.metadata,e.created_at
              FROM audit_event e LEFT JOIN app_user u ON u.id=e.actor_id
            """+filter+" ORDER BY e.created_at DESC,e.id DESC LIMIT :size OFFSET :offset").params(params).query((rs,n)->new AuditView(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),rs.getString("action"),rs.getString("subject_type"),rs.getObject("subject_id",UUID.class),rs.getString("trace_id"),json.readValue(rs.getString("metadata"),new TypeReference<Map<String,Object>>() {}),rs.getTimestamp("created_at").toInstant())).list();
        return new PageResult<>(items,total,page,size);
    }
    @GetMapping("/filters") public AuditFilters filters() {
        CurrentUser.get().requireRole("ADMIN");
        var actions=jdbc.sql("SELECT DISTINCT action FROM audit_event ORDER BY action").query(String.class).list();
        var actors=jdbc.sql("""
            SELECT e.actor_id,max(u.display_name) AS display_name,max(u.username) AS username
              FROM audit_event e LEFT JOIN app_user u ON u.id=e.actor_id
             GROUP BY e.actor_id ORDER BY e.actor_id NULLS FIRST
            """).query((rs,n)->{
                UUID id=rs.getObject("actor_id",UUID.class);
                String display=rs.getString("display_name"),username=rs.getString("username");
                return id==null?new ActorOption("SYSTEM","系统"):new ActorOption(id.toString(),display!=null?display:username!=null?username:id.toString());
            }).list();
        return new AuditFilters(actions,actors);
    }
    public record AuditView(UUID id,UUID actorId,String actorName,String action,String subjectType,UUID subjectId,String traceId,Map<String,Object> metadata,Instant createdAt) {}
    public record ActorOption(String value,String label) {}
    public record AuditFilters(List<String> actions,List<ActorOption> actors) {}
}
