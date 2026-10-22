package com.ticknova.audit;

import com.ticknova.shared.Actor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public AuditService(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public void record(Actor actor, String action, String subjectType, UUID subjectId, Map<String, Object> metadata) {
        jdbc.sql("INSERT INTO audit_event(id,actor_id,action,subject_type,subject_id,trace_id,metadata) VALUES(:id,:actor,:action,:type,:subject,:trace,CAST(:metadata AS jsonb))")
            .param("id", UUID.randomUUID()).param("actor", actor == null ? null : actor.id())
            .param("action", action).param("type", subjectType).param("subject", subjectId)
            .param("trace", MDC.get("traceId")).param("metadata", json.writeValueAsString(metadata)).update();
    }
}
