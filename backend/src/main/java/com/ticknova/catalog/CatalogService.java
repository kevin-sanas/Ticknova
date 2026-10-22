package com.ticknova.catalog;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.Actor;
import com.ticknova.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public CatalogService(JdbcClient jdbc, AuditService audit) { this.jdbc=jdbc; this.audit=audit; }

    public boolean canReadQueue(Actor actor, UUID queueId) {
        return actor.hasRole("AGENT") && membership(actor.id(),queueId,false);
    }

    public boolean canProcessQueue(Actor actor, UUID queueId) {
        return actor.hasRole("AGENT") && membership(actor.id(),queueId,true);
    }

    public void requireProcessQueue(Actor actor, UUID queueId) {
        if (!canProcessQueue(actor,queueId)) throw new ApiException(403,"QUEUE_FORBIDDEN","无权处理该队列");
    }

    private boolean membership(UUID userId, UUID queueId, boolean process) {
        if (queueId==null) return false;
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM queue_member m JOIN support_queue q ON q.id=m.queue_id
              WHERE m.user_id=:user AND m.queue_id=:queue AND q.active AND (:process=false OR m.can_process))
            """).param("user",userId).param("queue",queueId).param("process",process).query(Boolean.class).single();
    }

    public boolean eligibleAssignee(UUID queueId, UUID userId) {
        if (queueId==null || userId==null) return false;
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM queue_member m JOIN support_queue q ON q.id=m.queue_id
              JOIN app_user u ON u.id=m.user_id
              WHERE m.queue_id=:queue AND m.user_id=:user AND m.can_process AND q.active AND u.active
              AND EXISTS(SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='AGENT'))
            """).param("queue",queueId).param("user",userId).query(Boolean.class).single();
    }

    public UUID generalQueueId() {
        return jdbc.sql("SELECT id FROM support_queue WHERE code='GENERAL' AND active").query(UUID.class).optional()
            .orElseThrow(() -> new ApiException(409,"GENERAL_QUEUE_UNAVAILABLE","通用支持队列不可用，请联系管理员"));
    }

    public List<QueueView> queues(Actor actor) {
        if (actor.hasRole("ADMIN")) {
            return jdbc.sql("""
                SELECT q.id,q.code,q.name,q.active,COALESCE(m.can_process,false) AND q.active AS can_process
                  FROM support_queue q LEFT JOIN queue_member m ON m.queue_id=q.id AND m.user_id=:user
                 ORDER BY q.code
                """).param("user",actor.id()).query((rs,n)->new QueueView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getBoolean("active"),actor.hasRole("AGENT")&&rs.getBoolean("can_process"))).list();
        }
        if (!actor.hasRole("AGENT")) return List.of();
        return jdbc.sql("""
            SELECT q.id,q.code,q.name,q.active,m.can_process FROM support_queue q JOIN queue_member m ON m.queue_id=q.id
             WHERE m.user_id=:user AND q.active ORDER BY q.code
            """).param("user",actor.id()).query((rs,n)->new QueueView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getBoolean("active"),rs.getBoolean("can_process"))).list();
    }

    @Transactional
    public QueueView createQueue(Actor actor,String code,String name,boolean active) {
        actor.requireRole("ADMIN"); validateConfig(code,name,80);
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO support_queue(id,code,name,active) VALUES(:id,:code,:name,:active)")
            .param("id",id).param("code",code).param("name",name.trim()).param("active",active).update();
        audit.record(actor,"QUEUE_CREATED","QUEUE",id,Map.of("active",active));
        return new QueueView(id,code,name.trim(),active,false);
    }

    @Transactional
    public QueueView patchQueue(Actor actor,UUID id,String code,String name,Boolean active) {
        actor.requireRole("ADMIN");
        QueueView old=queue(id);
        String nextCode=code==null?old.code():code;
        String nextName=name==null?old.name():name;
        boolean nextActive=active==null?old.active():active;
        validateConfig(nextCode,nextName,80);
        jdbc.sql("UPDATE support_queue SET code=:code,name=:name,active=:active WHERE id=:id")
            .param("id",id).param("code",nextCode).param("name",nextName.trim()).param("active",nextActive).update();
        audit.record(actor,"QUEUE_UPDATED","QUEUE",id,Map.of("active",nextActive));
        return new QueueView(id,nextCode,nextName.trim(),nextActive,canProcessQueue(actor,id));
    }

    private QueueView queue(UUID id) {
        return jdbc.sql("SELECT id,code,name,active FROM support_queue WHERE id=:id").param("id",id)
            .query((rs,n)->new QueueView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getBoolean("active"),false)).optional()
            .orElseThrow(() -> new ApiException(404,"QUEUE_NOT_FOUND","队列不存在"));
    }

    public List<MemberView> members(Actor actor,UUID queueId) {
        if (!actor.hasRole("ADMIN") && !canReadQueue(actor,queueId)) throw new ApiException(403,"QUEUE_FORBIDDEN","无权查看该队列");
        queue(queueId);
        return jdbc.sql("""
            SELECT u.id,u.username,u.display_name,u.active,m.can_process FROM queue_member m JOIN app_user u ON u.id=m.user_id
             WHERE m.queue_id=:queue ORDER BY u.username
            """).param("queue",queueId).query((rs,n)->new MemberView(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("active"),rs.getBoolean("can_process"))).list();
    }

    @Transactional
    public void setMembership(Actor actor,UUID queueId,UUID userId,boolean canProcess) {
        actor.requireRole("ADMIN"); queue(queueId);
        boolean eligible=jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM app_user u WHERE u.id=:user AND u.active AND EXISTS(
              SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='AGENT'))
            """).param("user",userId).query(Boolean.class).single();
        if (!eligible) throw new ApiException(400,"INVALID_QUEUE_MEMBER","队列成员必须是启用的工程师");
        jdbc.sql("""
            INSERT INTO queue_member(queue_id,user_id,can_process) VALUES(:queue,:user,:process)
            ON CONFLICT(queue_id,user_id) DO UPDATE SET can_process=EXCLUDED.can_process
            """).param("queue",queueId).param("user",userId).param("process",canProcess).update();
        audit.record(actor,"QUEUE_MEMBERSHIP_SET","QUEUE",queueId,Map.of("userId",userId.toString(),"canProcess",canProcess));
    }

    @Transactional
    public void removeMembership(Actor actor,UUID queueId,UUID userId) {
        actor.requireRole("ADMIN"); queue(queueId);
        jdbc.sql("DELETE FROM queue_member WHERE queue_id=:queue AND user_id=:user").param("queue",queueId).param("user",userId).update();
        audit.record(actor,"QUEUE_MEMBERSHIP_REMOVED","QUEUE",queueId,Map.of("userId",userId.toString()));
    }

    public List<ServiceView> services(Actor actor,boolean includeInactive) {
        if (includeInactive) actor.requireRole("ADMIN");
        return jdbc.sql("SELECT id,code,name,active FROM service_catalog WHERE (:all=true OR active) ORDER BY code")
            .param("all",includeInactive).query((rs,n)->new ServiceView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getBoolean("active"))).list();
    }

    @Transactional
    public ServiceView createService(Actor actor,String code,String name,boolean active) {
        actor.requireRole("ADMIN"); validateConfig(code,name,60);
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO service_catalog(id,code,name,active) VALUES(:id,:code,:name,:active)")
            .param("id",id).param("code",code).param("name",name.trim()).param("active",active).update();
        audit.record(actor,"SERVICE_CREATED","SERVICE",id,Map.of("active",active));
        return new ServiceView(id,code,name.trim(),active);
    }

    @Transactional
    public ServiceView patchService(Actor actor,UUID id,String code,String name,Boolean active) {
        actor.requireRole("ADMIN");
        ServiceView old=jdbc.sql("SELECT id,code,name,active FROM service_catalog WHERE id=:id").param("id",id)
            .query((rs,n)->new ServiceView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getBoolean("active"))).optional()
            .orElseThrow(() -> new ApiException(404,"SERVICE_NOT_FOUND","服务不存在"));
        String nextCode=code==null?old.code():code;
        String nextName=name==null?old.name():name;
        boolean nextActive=active==null?old.active():active;
        validateConfig(nextCode,nextName,60);
        jdbc.sql("UPDATE service_catalog SET code=:code,name=:name,active=:active WHERE id=:id")
            .param("id",id).param("code",nextCode).param("name",nextName.trim()).param("active",nextActive).update();
        audit.record(actor,"SERVICE_UPDATED","SERVICE",id,Map.of("active",nextActive));
        return new ServiceView(id,nextCode,nextName.trim(),nextActive);
    }

    private static void validateConfig(String code,String name,int maxCode) {
        if (code==null || !code.matches("[A-Z][A-Z0-9_-]{0,"+(maxCode-1)+"}") || name==null || name.isBlank() || name.trim().length()>160)
            throw new ApiException(400,"INVALID_CONFIGURATION","编码或名称格式无效");
    }

    public record QueueView(UUID id,String code,String name,boolean active,boolean canProcess) {}
    public record MemberView(UUID userId,String username,String displayName,boolean active,boolean canProcess) {}
    public record ServiceView(UUID id,String code,String name,boolean active) {}
}
