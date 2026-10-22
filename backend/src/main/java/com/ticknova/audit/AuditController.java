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
                                                @RequestParam(required=false) String action) {
        CurrentUser.get().requireRole("ADMIN"); PageResult.validate(page,size);
        String normalized=action==null?"":action.trim();
        if(!normalized.isEmpty()&&!normalized.matches("[A-Z][A-Z0-9_]{0,99}"))throw new ApiException(400,"INVALID_AUDIT_ACTION","审计动作编码无效");
        String filter=normalized.isEmpty()?"":" WHERE e.action=:action";
        Map<String,Object> params=new HashMap<>();if(!normalized.isEmpty())params.put("action",normalized);
        long total=jdbc.sql("SELECT count(*) FROM audit_event e"+filter).params(params).query(Long.class).single();
        params.put("size",size);params.put("offset",(long)page*size);
        var items=jdbc.sql("""
            SELECT e.id,e.actor_id,u.display_name AS actor_name,e.action,e.subject_type,e.subject_id,e.trace_id,e.metadata,e.created_at
              FROM audit_event e LEFT JOIN app_user u ON u.id=e.actor_id
            """+filter+" ORDER BY e.created_at DESC,e.id DESC LIMIT :size OFFSET :offset").params(params).query((rs,n)->new AuditView(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),rs.getString("action"),rs.getString("subject_type"),rs.getObject("subject_id",UUID.class),rs.getString("trace_id"),json.readValue(rs.getString("metadata"),new TypeReference<Map<String,Object>>() {}),rs.getTimestamp("created_at").toInstant())).list();
        return new PageResult<>(items,total,page,size);
    }
    public record AuditView(UUID id,UUID actorId,String actorName,String action,String subjectType,UUID subjectId,String traceId,Map<String,Object> metadata,Instant createdAt) {}
}
