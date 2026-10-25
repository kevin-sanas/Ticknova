package com.ticknova.identity;

import com.ticknova.audit.AuditService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix="ticknova.seed",name="enabled",havingValue="true")
public class DemoSeed implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String password;
    public DemoSeed(JdbcClient jdbc,PasswordEncoder encoder,AuditService audit,@Value("${ticknova.seed.password:}") String password) {
        this.jdbc=jdbc; this.encoder=encoder; this.audit=audit; this.password=password;
        PasswordPolicy.validate(password);
    }

    @Override @Transactional public void run(ApplicationArguments args) {
        jdbc.sql("SELECT pg_advisory_xact_lock(873943626)").query((rs,n)->Boolean.TRUE).list();
        if (jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()>0) return;
        UUID general=jdbc.sql("SELECT id FROM support_queue WHERE code='GENERAL'").query(UUID.class).single();
        for (var seed:List.of(new SeedUser("admin","系统管理员","ADMIN"),new SeedUser("agent","支持工程师","AGENT"),new SeedUser("requester","服务请求者","REQUESTER"),new SeedUser("editor","知识编辑者","KB_EDITOR"),new SeedUser("reviewer","知识审核者","KB_REVIEWER"))) {
            UUID id=UUID.randomUUID();
            jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:username,:name,:hash,true)")
                .param("id",id).param("username",seed.username()).param("name",seed.name()).param("hash",encoder.encode(password)).update();
            jdbc.sql("INSERT INTO user_role(user_id,role_id) SELECT :user,id FROM app_role WHERE code=:role").param("user",id).param("role",seed.role()).update();
            if (seed.role().equals("AGENT")) jdbc.sql("INSERT INTO queue_member(queue_id,user_id,can_process) VALUES(:queue,:user,true)").param("queue",general).param("user",id).update();
        }
        jdbc.sql("INSERT INTO service_catalog(id,code,name,active) VALUES(:id,'GENERAL','通用服务',true) ON CONFLICT(code) DO NOTHING").param("id",UUID.randomUUID()).update();
        audit.record(null,"DEMO_SEEDED","SYSTEM",null,Map.of("usersCreated",5));
    }
    private record SeedUser(String username,String name,String role) {}
}
