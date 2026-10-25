package com.ticknova.identity;

import com.ticknova.catalog.CatalogService;
import com.ticknova.shared.Actor;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class IdentityIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionTokens tokens;
    @Autowired CatalogService catalog;
    @Autowired IdentityRepository users;
    @Autowired ApplicationContext context;
    @Autowired FilterChainProxy securityFilterChain;
    final HttpClient http=HttpClient.newHttpClient();
    String password;
    UUID admin,requester,agent;
    String adminToken,requesterToken,agentToken;
    final UUID general=UUID.fromString("20000000-0000-0000-0000-000000000001");

    @BeforeEach void fixture() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).as("Destructive fixture is restricted to isolated test DB").isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE api_idempotency,ticket_label,ticket_transition,ticket_comment,ticket,user_session,queue_member,user_role,audit_event,app_user,service_catalog CASCADE").update();
        jdbc.sql("DELETE FROM support_queue WHERE code <> 'GENERAL'").update();
        jdbc.sql("UPDATE support_queue SET active=true WHERE code='GENERAL'").update();
        password=UUID.randomUUID().toString();
        admin=user("admin",Set.of("ADMIN"),false); requester=user("requester",Set.of("REQUESTER"),false); agent=user("agent",Set.of("AGENT"),false);
        adminToken=session(admin); requesterToken=session(requester); agentToken=session(agent);
    }

    @Test void firstPasswordChangeRevokesEverySessionAndRequiresFreshLogin() throws Exception {
        UUID first=user("first",Set.of("REQUESTER"),true);
        String secondSession=session(first);
        var login=call("POST","/auth/login",null,Map.of("username","first","password",password));
        assertThat(login.status).as(login.body.toString()).isEqualTo(200);
        String token=login.body.path("accessToken").asText();
        assertThat(login.body.path("expiresIn").asInt()).isEqualTo(1800);
        assertThat(login.body.path("user").path("mustChangePassword").asBoolean()).isTrue();
        assertThat(call("GET","/me",token,null).status).isEqualTo(200);
        assertThat(call("GET","/services",token,null).body.path("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        String nextPassword=UUID.randomUUID().toString();
        assertThat(call("POST","/auth/change-password",token,Map.of("oldPassword",password,"newPassword",nextPassword)).status).isEqualTo(204);
        assertThat(call("GET","/me",token,null).status).isEqualTo(401);
        assertThat(call("GET","/me",secondSession,null).status).isEqualTo(401);
        assertThat(call("POST","/auth/login",null,Map.of("username","first","password",password)).status).isEqualTo(401);
        var nextLogin=call("POST","/auth/login",null,Map.of("username","first","password",nextPassword));
        assertThat(nextLogin.status).isEqualTo(200);
        assertThat(nextLogin.body.path("user").path("mustChangePassword").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM user_session WHERE user_id=:id AND revoked_at IS NULL").param("id",first).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT string_agg(metadata::text,',') FROM audit_event").query(String.class).single()).doesNotContain(password,nextPassword,token,secondSession);
    }

    @Test void logoutRevokesOnlyTheCurrentOpaqueSession() throws Exception {
        String another=session(requester);
        assertThat(call("POST","/auth/logout",requesterToken,null).status).isEqualTo(204);
        assertThat(call("GET","/me",requesterToken,null).status).isEqualTo(401);
        assertThat(call("GET","/me",another,null).status).isEqualTo(200);
    }

    @Test void stalePasswordVerificationCannotCreateASessionAfterPasswordChange() {
        String oldHash=users.findById(requester).orElseThrow().passwordHash();
        jdbc.sql("UPDATE app_user SET password_hash=:hash WHERE id=:id")
            .param("id",requester).param("hash",new BCryptPasswordEncoder(4).encode(UUID.randomUUID().toString())).update();
        var token=tokens.generate();
        assertThat(users.createSessionIfPasswordCurrent(requester,oldHash,token.hash(),java.time.Instant.now().plusSeconds(1800))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM user_session WHERE token_hash=:hash").param("hash",token.hash()).query(Long.class).single()).isZero();
    }

    @Test void rolesAndActiveStatusAreReloadedForExistingSessions() throws Exception {
        assertThat(call("GET","/users",requesterToken,null).status).isEqualTo(403);
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code='ADMIN'").param("id",requester).update();
        assertThat(call("GET","/users",requesterToken,null).status).isEqualTo(200);
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id AND role_id=(SELECT id FROM app_role WHERE code='ADMIN')").param("id",requester).update();
        assertThat(call("GET","/users",requesterToken,null).status).isEqualTo(403);
        jdbc.sql("UPDATE app_user SET active=false WHERE id=:id").param("id",requester).update();
        assertThat(call("GET","/me",requesterToken,null).status).isEqualTo(401);
    }

    @Test void adminCreatesAccountsAndCannotRemoveTheLastActiveAdmin() throws Exception {
        assertThat(call("PATCH","/users/"+admin,adminToken,Map.of("active",false)).body.path("code").asText()).isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(call("PUT","/users/"+admin+"/roles",adminToken,Map.of("roles",List.of("REQUESTER"))).status).isEqualTo(409);
        var created=call("POST","/users",adminToken,Map.of("username","new-agent","displayName","New Agent","password",UUID.randomUUID().toString(),"roles",List.of("AGENT")));
        assertThat(created.status).as(created.body.toString()).isEqualTo(201);
        assertThat(created.body.path("mustChangePassword").asBoolean()).isTrue();
        assertThat(created.body.toString()).doesNotContain("password");
        assertThat(call("PUT","/users/"+created.body.path("id").asText()+"/roles",requesterToken,Map.of("roles",List.of("ADMIN"))).status).isEqualTo(403);
        assertThat(call("GET","/users?size=101",adminToken,null).status).isEqualTo(400);
        assertThat(call("GET","/audit-events",requesterToken,null).status).isEqualTo(403);
        assertThat(call("GET","/audit-events",adminToken,null).body.path("total").asInt()).isGreaterThan(0);
    }

    @Test void auditFiltersExposeActualValuesAndCombineActorActionAndUtcRange() throws Exception {
        assertThat(call("POST","/auth/login",null,Map.of("username","requester","password",password)).status).isEqualTo(200);
        assertThat(call("POST","/auth/login",null,Map.of("username","missing","password","invalid-password")).status).isEqualTo(401);

        var filters=call("GET","/audit-events/filters",adminToken,null);
        assertThat(filters.status).isEqualTo(200);
        assertThat(filters.body.path("actions")).anySatisfy(action->assertThat(action.asText()).isEqualTo("AUTH_LOGIN"));
        assertThat(filters.body.path("actors")).anySatisfy(actor->assertThat(actor.path("value").asText()).isEqualTo(requester.toString()));
        assertThat(filters.body.path("actors")).anySatisfy(actor->assertThat(actor.path("value").asText()).isEqualTo("SYSTEM"));

        String from=Instant.now().minusSeconds(60).toString();
        String to=Instant.now().plusSeconds(60).toString();
        var rows=call("GET","/audit-events?action=AUTH_LOGIN&actor="+requester+"&from="+from+"&to="+to,adminToken,null);
        assertThat(rows.status).isEqualTo(200);
        assertThat(rows.body.path("total").asInt()).isEqualTo(1);
        assertThat(rows.body.path("items").get(0).path("action").asText()).isEqualTo("AUTH_LOGIN");
        assertThat(rows.body.path("items").get(0).path("actorId").asText()).isEqualTo(requester.toString());

        var systemRows=call("GET","/audit-events?actor=SYSTEM",adminToken,null);
        assertThat(systemRows.status).isEqualTo(200);
        assertThat(systemRows.body.path("items")).allSatisfy(event->assertThat(event.path("actorId").isNull()).isTrue());
        assertThat(call("GET","/audit-events?from="+to+"&to="+from,adminToken,null).status).isEqualTo(400);
    }

    @Test void adminManagementListsSearchFilterAndPaginateWithoutChangingPublicCatalogs() throws Exception {
        assertThat(call("POST","/users",adminToken,Map.of("username","search.agent","displayName","专项检索工程师","password",UUID.randomUUID().toString(),"roles",List.of("AGENT"))).status).isEqualTo(201);
        assertThat(call("POST","/users",adminToken,Map.of("username","search.requester","displayName","其他申请人","password",UUID.randomUUID().toString(),"roles",List.of("REQUESTER"))).status).isEqualTo(201);
        var userRows=call("GET","/users?q=%E4%B8%93%E9%A1%B9&active=true&role=AGENT&page=0&size=20",adminToken,null);
        assertThat(userRows.status).isEqualTo(200);
        assertThat(userRows.body.path("total").asInt()).isEqualTo(1);
        assertThat(userRows.body.path("items").get(0).path("username").asText()).isEqualTo("search.agent");
        assertThat(call("GET","/users?role=UNKNOWN",adminToken,null).status).isEqualTo(400);

        assertThat(call("POST","/queues",adminToken,Map.of("code","ARCHIVE_SUPPORT","name","归档支持队列","active",false)).status).isEqualTo(201);
        assertThat(call("POST","/services",adminToken,Map.of("code","MAIL","name","企业邮箱服务","active",true)).status).isEqualTo(201);
        assertThat(call("POST","/services",adminToken,Map.of("code","OLD_MAIL","name","旧邮箱服务","active",false)).status).isEqualTo(201);
        var queueRows=call("GET","/admin/queues?q=%E5%BD%92%E6%A1%A3&active=false&page=0&size=20",adminToken,null);
        assertThat(queueRows.status).isEqualTo(200);
        assertThat(queueRows.body.path("total").asInt()).isEqualTo(1);
        assertThat(queueRows.body.path("items").get(0).path("code").asText()).isEqualTo("ARCHIVE_SUPPORT");
        var serviceRows=call("GET","/admin/services?q=%E9%82%AE%E7%AE%B1&active=true&page=0&size=20",adminToken,null);
        assertThat(serviceRows.status).isEqualTo(200);
        assertThat(serviceRows.body.path("total").asInt()).isEqualTo(1);
        assertThat(serviceRows.body.path("items").get(0).path("code").asText()).isEqualTo("MAIL");
        assertThat(call("GET","/admin/queues",requesterToken,null).status).isEqualTo(403);
        assertThat(call("GET","/admin/services",requesterToken,null).status).isEqualTo(403);
        assertThat(call("GET","/queues",requesterToken,null).body.size()).isZero();
        assertThat(call("GET","/services",requesterToken,null).body.size()).isEqualTo(1);
    }

    @Test void concurrentAdminDeactivationKeepsOneActiveAdmin() throws Exception {
        UUID other=user("other-admin",Set.of("ADMIN"),false); String otherToken=session(other);
        var start=new CountDownLatch(1); var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->{ start.await(); return call("PATCH","/users/"+admin,adminToken,Map.of("active",false)).status; });
            var b=executor.submit(()->{ start.await(); return call("PATCH","/users/"+other,otherToken,Map.of("active",false)).status; });
            start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.sql("SELECT count(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id WHERE u.active AND r.code='ADMIN'").query(Long.class).single()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void queueMembershipSeparatesReadingFromProcessingAndDoesNotInheritAdmin() throws Exception {
        var created=call("POST","/queues",adminToken,Map.of("code","SUPPORT","name","支持"));
        assertThat(created.status).as(created.body.toString()).isEqualTo(201);
        UUID queue=UUID.fromString(created.body.path("id").asText());
        assertThat(call("PUT","/queues/"+queue+"/members/"+agent,adminToken,Map.of("canProcess",false)).status).isEqualTo(204);
        var actor=new Actor(agent,"agent","agent",Set.of("AGENT"),false);
        assertThat(catalog.canReadQueue(actor,queue)).isTrue();
        assertThat(catalog.canProcessQueue(actor,queue)).isFalse();
        assertThat(catalog.eligibleAssignee(queue,agent)).isFalse();
        assertThat(call("GET","/queues/"+queue+"/members",agentToken,null).status).isEqualTo(200);
        assertThat(call("GET","/queues/"+queue+"/members",requesterToken,null).status).isEqualTo(403);
        assertThat(call("GET","/queues",requesterToken,null).body.size()).isZero();
        assertThat(catalog.canReadQueue(new Actor(admin,"admin","admin",Set.of("ADMIN"),false),queue)).isFalse();
        assertThat(call("PUT","/queues/"+queue+"/members/"+agent,adminToken,Map.of("canProcess",true)).status).isEqualTo(204);
        assertThat(catalog.eligibleAssignee(queue,agent)).isTrue();
        assertThat(call("DELETE","/queues/"+queue+"/members/"+agent,adminToken,null).status).isEqualTo(204);
        assertThat(catalog.canReadQueue(actor,queue)).isFalse();
        assertThat(call("POST","/services",adminToken,Map.of("code","OFFLINE","name","停用服务","active",false)).status).isEqualTo(201);
        assertThat(call("GET","/services",requesterToken,null).body.size()).isZero();
        assertThat(call("GET","/services?includeInactive=true",adminToken,null).body.size()).isEqualTo(1);
        assertThat(call("GET","/services?includeInactive=true",requesterToken,null).status).isEqualTo(403);
    }

    @Test void errorResponsesHaveTraceIdsAndNeverEchoSecrets() throws Exception {
        var invalid=call("POST","/auth/login",null,Map.of("password",password));
        assertThat(invalid.status).isEqualTo(400);
        assertThat(invalid.body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(invalid.body.path("traceId").asText()).isNotBlank();
        assertThat(invalid.body.path("errors").has("username")).isTrue();
        assertThat(invalid.body.toString()).doesNotContain(password,"stackTrace","password_hash");
        assertThat(call("PATCH","/users/not-a-uuid",adminToken,Map.of("active",true)).status).isEqualTo(400);
    }

    @Test void rejectedBearerSessionIsAuditedWithoutRecordingTheToken() throws Exception {
        String invalid=tokens.generate().token();
        assertThat(call("GET","/me",invalid,null).status).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AUTH_SESSION_REJECTED'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE action='AUTH_SESSION_REJECTED'").query(String.class).single()).doesNotContain(invalid);
    }

    @Test void bearerOnlyApplicationHasNoFallbackUserDetailsServiceOrBasicAuthentication() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThat(securityFilterChain.getFilterChains().stream().flatMap(chain -> chain.getFilters().stream()))
            .noneMatch(filter -> filter instanceof BasicAuthenticationFilter);
    }

    UUID user(String username,Set<String> roles,boolean firstPassword) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:name,:name,:hash,:first)")
            .param("id",id).param("name",username).param("hash",new BCryptPasswordEncoder(4).encode(password)).param("first",firstPassword).update();
        for (String role:roles) jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code=:role").param("id",id).param("role",role).update();
        return id;
    }
    String session(UUID user) {
        var token=tokens.generate();
        jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:user,:hash,now()+interval '30 minutes')")
            .param("id",UUID.randomUUID()).param("user",user).param("hash",token.hash()).update();
        return token.token();
    }
    Reply call(String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if (token!=null) request.header("Authorization","Bearer "+token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.body().isBlank()?json.createObjectNode():json.readTree(response.body()));
    }
    record Reply(int status,JsonNode body) {}
}
