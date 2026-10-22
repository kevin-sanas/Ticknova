package com.ticknova.identity;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminService {
    private static final Set<String> ROLES=Set.of("ADMIN","AGENT","REQUESTER","KB_EDITOR");
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    public UserAdminService(JdbcClient jdbc,PasswordEncoder encoder,AuditService audit) { this.jdbc=jdbc; this.encoder=encoder; this.audit=audit; }

    public List<String> roles(Actor actor) { actor.requireRole("ADMIN"); return ROLES.stream().sorted().toList(); }

    public PageResult<UserView> list(Actor actor,int page,int size) {
        actor.requireRole("ADMIN"); PageResult.validate(page,size);
        long total=jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
        var items=jdbc.sql("SELECT id,username,display_name,active,must_change_password,created_at FROM app_user ORDER BY username,id LIMIT :size OFFSET :offset")
            .param("size",size).param("offset",(long)page*size).query((rs,n)->new UserView(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),userRoles(rs.getObject("id",UUID.class)),rs.getTimestamp("created_at").toInstant())).list();
        return new PageResult<>(items,total,page,size);
    }

    @Transactional
    public UserView create(Actor actor,String username,String displayName,String password,Set<String> roles) {
        actor.requireRole("ADMIN"); validateRoles(roles); PasswordPolicy.validate(password); validateName(displayName);
        if (username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,79}"))
            throw new ApiException(400,"INVALID_USERNAME","用户名须为 3–80 个字母、数字或 ._- 字符");
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:username,:name,:hash,true)")
            .param("id",id).param("username",username).param("name",displayName.trim()).param("hash",encoder.encode(password)).update();
        insertRoles(id,roles);
        audit.record(actor,"USER_CREATED","USER",id,Map.of("roles",roles));
        return user(id);
    }

    @Transactional
    public UserView patch(Actor actor,UUID id,String displayName,Boolean active) {
        actor.requireRole("ADMIN"); lockAdminChanges();
        var previous=user(id);
        if (displayName==null && active==null) throw new ApiException(400,"EMPTY_UPDATE","请提供需要修改的字段");
        if (displayName!=null) validateName(displayName);
        if (Boolean.FALSE.equals(active) && previous.active() && previous.roles().contains("ADMIN")) protectLastAdmin();
        jdbc.sql("UPDATE app_user SET display_name=:name,active=:active WHERE id=:id")
            .param("id",id).param("name",displayName==null?previous.displayName():displayName.trim()).param("active",active==null?previous.active():active).update();
        if (Boolean.FALSE.equals(active)) jdbc.sql("UPDATE user_session SET revoked_at=now() WHERE user_id=:id AND revoked_at IS NULL").param("id",id).update();
        audit.record(actor,"USER_UPDATED","USER",id,Map.of("active",active==null?previous.active():active));
        return user(id);
    }

    @Transactional
    public void setRoles(Actor actor,UUID id,Set<String> roles) {
        actor.requireRole("ADMIN"); validateRoles(roles); lockAdminChanges();
        var previous=user(id);
        if (previous.active() && previous.roles().contains("ADMIN") && !roles.contains("ADMIN")) protectLastAdmin();
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id").param("id",id).update();
        insertRoles(id,roles);
        audit.record(actor,"USER_ROLES_CHANGED","USER",id,Map.of("roles",roles));
    }

    private void lockAdminChanges() {
        // One transaction-scoped lock serializes role removals and user deactivations across API replicas.
        jdbc.sql("SELECT pg_advisory_xact_lock(873943625)").query((rs,n)->Boolean.TRUE).list();
    }

    private void protectLastAdmin() {
        long count=jdbc.sql("SELECT count(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id WHERE u.active AND r.code='ADMIN'").query(Long.class).single();
        if (count<=1) throw new ApiException(409,"LAST_ADMIN_REQUIRED","必须保留至少一位启用的管理员");
    }

    private UserView user(UUID id) {
        return jdbc.sql("SELECT id,username,display_name,active,must_change_password,created_at FROM app_user WHERE id=:id")
            .param("id",id).query((rs,n)->new UserView(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),userRoles(id),rs.getTimestamp("created_at").toInstant())).optional()
            .orElseThrow(() -> new ApiException(404,"USER_NOT_FOUND","用户不存在"));
    }

    private Set<String> userRoles(UUID id) {
        return Set.copyOf(jdbc.sql("SELECT r.code FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=:id ORDER BY r.code").param("id",id).query(String.class).list());
    }

    private void insertRoles(UUID id,Set<String> roles) {
        for (String role:roles) jdbc.sql("INSERT INTO user_role(user_id,role_id) SELECT :id,id FROM app_role WHERE code=:role")
            .param("id",id).param("role",role).update();
    }

    private static void validateRoles(Set<String> roles) {
        if (roles==null || roles.isEmpty() || !ROLES.containsAll(roles)) throw new ApiException(400,"INVALID_ROLES","角色列表无效");
    }
    private static void validateName(String name) {
        if (name==null || name.isBlank() || name.trim().length()>120) throw new ApiException(400,"INVALID_DISPLAY_NAME","显示名称须为 1–120 个字符");
    }
    public record UserView(UUID id,String username,String displayName,boolean active,boolean mustChangePassword,Set<String> roles,Instant createdAt) {}
}
