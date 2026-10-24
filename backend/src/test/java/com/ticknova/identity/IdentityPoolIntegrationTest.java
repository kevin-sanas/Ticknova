package com.ticknova.identity;

import com.ticknova.shared.Actor;
import com.ticknova.audit.AuditService;
import com.zaxxer.hikari.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(properties={"ticknova.seed.enabled=false","ticknova.ai.worker-enabled=false","ticknova.knowledge.worker-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class IdentityPoolIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired HikariDataSource source;
    @Autowired PasswordEncoder encoder;
    @Autowired AuditService audit;
    IdentityRepository identities;
    UserAdminService users;
    HikariDataSource pool;
    UUID id;
    final String hash="a".repeat(64);
    @BeforeEach void fixture(){
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();id=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'pool-fixture','Pool fixture','not-a-login-hash',false)").param("id",id).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code IN ('ADMIN','AGENT')").param("id",id).update();
        jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:session,:user,:hash,now()+interval '30 minutes')").param("session",UUID.randomUUID()).param("user",id).param("hash",hash).update();
        // Flyway may use multiple startup connections. Restrict the application queries only.
        var config=new HikariConfig();config.setJdbcUrl(source.getJdbcUrl());config.setUsername(source.getUsername());config.setPassword(source.getPassword());config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionTimeout(300);
        pool=new HikariDataSource(config);var bounded=JdbcClient.create(pool);identities=new IdentityRepository(bounded);users=new UserAdminService(bounded,encoder,audit);
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    @Test void authenticationNeedsOnlyOneConnectionAndStillReadsCurrentRolesAndRevocation(){
        Actor first=assertDoesNotThrow(()->identities.sessionActor(hash).orElseThrow());
        assertThat(first.roles()).containsExactlyInAnyOrder("ADMIN","AGENT");
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id AND role_id=(SELECT id FROM app_role WHERE code='AGENT')").param("id",id).update();
        assertThat(identities.sessionActor(hash).orElseThrow().roles()).containsExactly("ADMIN");
        jdbc.sql("UPDATE user_session SET revoked_at=now() WHERE user_id=:id").param("id",id).update();
        assertThat(identities.sessionActor(hash)).isEmpty();
    }
    @Test void adminUserListDoesNotBorrowInsideAnOpenRowMapper(){
        var result=assertDoesNotThrow(()->users.list(new Actor(id,"pool-fixture","Pool fixture",Set.of("ADMIN"),false),0,20));
        assertThat(result.total()).isEqualTo(1);assertThat(result.items().getFirst().roles()).containsExactlyInAnyOrder("ADMIN","AGENT");
    }
}
