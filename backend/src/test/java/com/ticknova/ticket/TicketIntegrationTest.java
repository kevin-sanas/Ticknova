package com.ticknova.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class TicketIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.ticknova.identity.SessionTokens tokens;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TicketRepository observedRepository;
    final HttpClient http=HttpClient.newHttpClient();
    final UUID general=UUID.fromString("20000000-0000-0000-0000-000000000001");
    UUID alice,bob,agent,admin,otherQueue;
    String aliceToken,bobToken,agentToken,adminToken;
    String password="Fixture-only-Password-123!";
    record Reply(int status,JsonNode body) {}

    @BeforeEach void fixture() throws Exception {
        String db=jdbc.sql("SELECT current_database()").query(String.class).single();
        assertThat(db).as("Never run destructive fixtures against a non-test database").isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE api_idempotency,ticket_label,ticket_transition,ticket_comment,ticket,user_session,queue_member,user_role,audit_event,app_user,service_catalog CASCADE").update();
        jdbc.sql("DELETE FROM support_queue WHERE code <> 'GENERAL'").update();
        jdbc.sql("UPDATE support_queue SET active=true WHERE code='GENERAL'").update();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        alice=user("alice"+suffix,Set.of("REQUESTER")); bob=user("bob"+suffix,Set.of("REQUESTER"));
        agent=user("agent"+suffix,Set.of("AGENT"));admin=user("admin"+suffix,Set.of("ADMIN"));
        jdbc.sql("INSERT INTO queue_member(queue_id,user_id,can_process) VALUES(:q,:u,true)").param("q",general).param("u",agent).update();
        otherQueue=UUID.randomUUID();jdbc.sql("INSERT INTO support_queue(id,code,name) VALUES(:id,:code,'私有队列')").param("id",otherQueue).param("code","PRIVATE"+suffix).update();
        aliceToken=session(alice);bobToken=session(bob);agentToken=session(agent);adminToken=session(admin);
    }
    UUID user(String name,Set<String> roles) {
        UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,display_name,must_change_password) VALUES(:id,:name,:hash,:name,false)")
            .param("id",id).param("name",name).param("hash",new BCryptPasswordEncoder(4).encode(password)).update();
        for(String role:roles)jdbc.sql("INSERT INTO user_role SELECT :u,id FROM app_role WHERE code=:r").param("u",id).param("r",role).update();
        return id;
    }
    String login(String name) throws Exception {return call("POST","/auth/login",null,Map.of("username",name,"password",password),null).body.path("accessToken").asText();}
    String session(UUID userId) {
        var token=tokens.generate();
        jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:user,:hash,now()+interval '30 minutes')")
            .param("id",UUID.randomUUID()).param("user",userId).param("hash",token.hash()).update();
        return token.token();
    }
    Reply call(String method,String path,String token,Object body,String key) throws Exception {
        return call(method,path,token,body,key,Map.of());
    }
    Reply call(String method,String path,String token,Object body,String key,Map<String,String> headers) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        headers.forEach(b::header);
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(key!=null)b.header("Idempotency-Key",key);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(),r.body().isBlank()?json.createObjectNode():json.readTree(r.body()));
    }
    JsonNode create(String key) throws Exception {
        Reply r=call("POST","/tickets",aliceToken,Map.of("title","网关502","description","需要排查网关","priority","NORMAL"),key);
        assertThat(r.status).as(r.body.toString()).isEqualTo(201);return r.body;
    }
    Reply transition(String id,String token,String to,long version,String reason) throws Exception {
        return call("POST","/tickets/"+id+"/transitions",token,Map.of("toStatus",to,"version",version,"reason",reason),UUID.randomUUID().toString());
    }
    @Test void migrationAndQueueScopeKeepInternalNotesAndOtherTicketsPrivate() throws Exception {
        assertThat(jdbc.sql("SELECT extversion FROM pg_extension WHERE extname='vector'").query(String.class).single()).startsWith("0.8.");
        String id=create("create-1").path("id").asText();
        assertThat(call("POST","/tickets/"+id+"/comments",agentToken,Map.of("visibility","INTERNAL_NOTE","body","仅内部可见-秘密标记"),"note-1").status).isEqualTo(201);
        var owner=call("GET","/tickets/"+id,aliceToken,null,null);
        assertThat(owner.status).isEqualTo(200);assertThat(owner.body.path("internalNotes").size()).isZero();
        assertThat(owner.body.toString()).doesNotContain("秘密标记");
        assertThat(call("GET","/tickets/"+id,bobToken,null,null).status).isEqualTo(403);
        assertThat(call("GET","/tickets/"+id,adminToken,null,null).status).isEqualTo(403);
        assertThat(call("GET","/tickets",bobToken,null,null).body.path("total").asInt()).isZero();
        assertThat(call("POST","/tickets/"+id+"/comments",aliceToken,Map.of("visibility","INTERNAL_NOTE","body","不允许"),"bad-note").status).isEqualTo(403);
    }
    @Test void requesterAndEngineerCompleteTheWorkflowWithResolutionAndReopen() throws Exception {
        String id=create("workflow").path("id").asText();
        assertThat(transition(id,agentToken,"IN_PROGRESS",0,"跳过分诊").status).isEqualTo(409);
        assertThat(transition(id,agentToken,"TRIAGED",0,"已分诊").status).isEqualTo(200);
        assertThat(transition(id,agentToken,"IN_PROGRESS",1,"处理中").status).isEqualTo(200);
        assertThat(transition(id,agentToken,"RESOLVED",2,"").status).isEqualTo(400);
        assertThat(transition(id,agentToken,"RESOLVED",2,"已修复配置").status).isEqualTo(200);
        assertThat(transition(id,aliceToken,"IN_PROGRESS",3,"仍有问题").status).isEqualTo(200);
        assertThat(call("GET","/tickets/"+id,aliceToken,null,null).body.path("resolutionNote").isNull()).isTrue();
        assertThat(transition(id,agentToken,"RESOLVED",4,"第二次修复").status).isEqualTo(200);
        assertThat(transition(id,aliceToken,"CLOSED",5,"确认完成").status).isEqualTo(200);
        assertThat(transition(id,adminToken,"IN_PROGRESS",6,"重开").status).isEqualTo(403);
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code='ADMIN'").param("id",agent).update();
        assertThat(transition(id,agentToken,"IN_PROGRESS",6,"").status).isEqualTo(400);
        assertThat(transition(id,agentToken,"IN_PROGRESS",6,"管理员核准重开").status).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM ticket_transition WHERE ticket_id=CAST(:id AS uuid)").param("id",id).query(Long.class).single()).isEqualTo(7);
    }
    @Test void idempotencyReplaysConflictsRollsBackAndRechecksPermission() throws Exception {
        var first=create("same");var repeated=create("same");assertThat(repeated.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(call("POST","/tickets",aliceToken,Map.of("title","不同内容","description","文字","priority","NORMAL"),"same").status).isEqualTo(409);
        String id=first.path("id").asText();
        assertThat(call("POST","/tickets/"+id+"/comments",aliceToken,Map.of("body","非法内部","visibility","INTERNAL_NOTE"),"rollback").status).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM api_idempotency WHERE idempotency_key='rollback'").query(Long.class).single()).isZero();
        var req=Map.of("body","公开回复","visibility","PUBLIC_REPLY");
        assertThat(call("POST","/tickets/"+id+"/comments",agentToken,req,"comment-replay").status).isEqualTo(201);
        jdbc.sql("DELETE FROM queue_member WHERE user_id=:id").param("id",agent).update();
        assertThat(call("POST","/tickets/"+id+"/comments",agentToken,req,"comment-replay").status).isEqualTo(403);
    }
    @Test void simultaneousSameKeyNeverCreatesTwoTickets() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        Callable<Reply> request=()->{start.await();return call("POST","/tickets",aliceToken,Map.of("title","并发","description","相同请求","priority","NORMAL"),"parallel");};
        try {
            var a=executor.submit(request);var b=executor.submit(request);start.countDown();
            List<Integer> statuses=List.of(a.get().status,b.get().status);
            assertThat(statuses).contains(201);assertThat(statuses).allMatch(s->s==201||s==409);
            assertThat(jdbc.sql("SELECT count(*) FROM ticket").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='TICKET_CREATED'").query(Long.class).single()).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @Test void assignmentRequiresTargetScopeAndVersionPreventsLostUpdates() throws Exception {
        String id=create("assign").path("id").asText();
        assertThat(call("PUT","/tickets/"+id+"/assignment",agentToken,Map.of("queueId",otherQueue,"assigneeId",agent,"version",0),null).status).isEqualTo(403);
        assertThat(call("PUT","/tickets/"+id+"/assignment",agentToken,Map.of("queueId",general,"assigneeId",bob,"version",0),null).status).isEqualTo(400);
        assertThat(call("PUT","/tickets/"+id+"/assignment",agentToken,Map.of("queueId",general,"assigneeId",agent,"version",0),null).status).isEqualTo(200);
        assertThat(call("PATCH","/tickets/"+id,agentToken,Map.of("priority","HIGH","tags",List.of("网络"),"version",0),null).status).isEqualTo(409);
        assertThat(call("PATCH","/tickets/"+id,agentToken,Map.of("priority","HIGH","tags",List.of("网络"," 网络 "),"version",1),null).status).isEqualTo(200);
        assertThat(call("GET","/tickets?tag="+java.net.URLEncoder.encode("网络",java.nio.charset.StandardCharsets.UTF_8),agentToken,null,null).body.path("total").asInt()).isEqualTo(1);
        assertThat(call("GET","/tickets/"+id,aliceToken,null,null).body.path("ticket").path("tags").size()).isEqualTo(1);
    }
    @Test void expiredSessionAndInactiveUserCannotReplayWrites() throws Exception {
        create("auth-state");jdbc.sql("UPDATE user_session SET expires_at=:time WHERE user_id=:id").param("time",java.sql.Timestamp.from(Instant.now().minusSeconds(5))).param("id",alice).update();
        assertThat(call("GET","/tickets",aliceToken,null,null).status).isEqualTo(401);
        jdbc.sql("UPDATE app_user SET active=false WHERE id=:id").param("id",agent).update();
        assertThat(call("GET","/tickets",agentToken,null,null).status).isEqualTo(401);
    }
    @Test void authenticatedOpenApiDescribesImplementedContractsWithoutData() throws Exception {
        assertThat(call("GET","/openapi",null,null,null).status).isEqualTo(401);
        var response=call("GET","/openapi",adminToken,null,null);
        assertThat(response.status).isEqualTo(200);
        assertThat(response.body.path("paths").has("/api/v1/tickets/{id}/assignment")).isTrue();
        assertThat(response.body.path("paths").has("/api/v1/users/{id}/roles")).isTrue();
        assertThat(response.body.path("components").path("securitySchemes").has("bearerSession")).isTrue();
    }
    @Test void headerVersionAndTransitionReplayDoNotDuplicateHistory() throws Exception {
        String id=create("versions").path("id").asText();
        assertThat(call("PATCH","/tickets/"+id,agentToken,Map.of("priority","HIGH"),null).status).isEqualTo(400);
        assertThat(call("PATCH","/tickets/"+id,agentToken,Map.of("priority","HIGH","version",1),null,Map.of("If-Match","\"0\"")).status).isEqualTo(400);
        assertThat(call("PATCH","/tickets/"+id,agentToken,Map.of("priority","HIGH"),null,Map.of("If-Match","\"0\"")).status).isEqualTo(200);
        var payload=Map.of("toStatus","TRIAGED","reason","公开理由","version",1);
        assertThat(call("POST","/tickets/"+id+"/transitions",agentToken,payload,"transition-repeat").status).isEqualTo(200);
        assertThat(call("POST","/tickets/"+id+"/transitions",agentToken,payload,"transition-repeat").status).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM ticket_transition").query(Long.class).single()).isEqualTo(1);
        assertThat(call("GET","/tickets/"+id,aliceToken,null,null).body.path("ticket").path("createdAt").asText()).endsWith("Z");
    }
    @Test void readOnlyQueueMemberCanReadNotesButCannotMutate() throws Exception {
        String id=create("read-only").path("id").asText();
        assertThat(call("POST","/tickets/"+id+"/comments",agentToken,Map.of("visibility","INTERNAL_NOTE","body","工程师可读"),"read-only-note").status).isEqualTo(201);
        jdbc.sql("UPDATE queue_member SET can_process=false WHERE user_id=:id").param("id",agent).update();
        var detail=call("GET","/tickets/"+id,agentToken,null,null).body;
        assertThat(detail.path("internalNotes").size()).isEqualTo(1);
        assertThat(detail.path("capabilities").path("canReadInternal").asBoolean()).isTrue();
        assertThat(detail.path("capabilities").path("canCommentInternal").asBoolean()).isFalse();
        assertThat(transition(id,agentToken,"TRIAGED",0,"只读成员").status).isEqualTo(403);
    }
    @Test void auditFailureRollsBackTicketAndIdempotencyTogether() throws Exception {
        jdbc.sql("CREATE FUNCTION ticknova_test_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='TICKET_CREATED' THEN RAISE EXCEPTION 'injected audit failure'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER ticknova_test_audit_failure BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION ticknova_test_reject_audit()").update();
        try {
            assertThat(call("POST","/tickets",aliceToken,Map.of("title","事务测试","description","审计失败必须回滚"),"audit-rollback").status).isEqualTo(500);
            assertThat(jdbc.sql("SELECT count(*) FROM ticket").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM api_idempotency WHERE idempotency_key='audit-rollback'").query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER ticknova_test_audit_failure ON audit_event").update();
            jdbc.sql("DROP FUNCTION ticknova_test_reject_audit()").update();
        }
    }
    @Test void movingTicketCannotExposeNotesCreatedAfterTheAuthorizedReadSnapshot() throws Exception {
        String id=create("snapshot").path("id").asText();
        UUID mover=user("mover"+UUID.randomUUID().toString().substring(0,8),Set.of("AGENT"));
        for(UUID queue:List.of(general,otherQueue))jdbc.sql("INSERT INTO queue_member(queue_id,user_id) VALUES(:q,:u)").param("q",queue).param("u",mover).update();
        String moverToken=session(mover);
        var authorized=new CountDownLatch(1);var resume=new CountDownLatch(1);var paused=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            var actor=invocation.getArgument(0,com.ticknova.shared.Actor.class);
            if(actor.id().equals(agent)&&paused.compareAndSet(false,true)) {
                authorized.countDown();
                if(!resume.await(10,TimeUnit.SECONDS))throw new AssertionError("Concurrent reader was not resumed");
            }
            return result;
        }).when(observedRepository).visible(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var read=executor.submit(()->call("GET","/tickets/"+id,agentToken,null,null));
            assertThat(authorized.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(call("PUT","/tickets/"+id+"/assignment",moverToken,Map.of("queueId",otherQueue,"version",0),null).status).isEqualTo(200);
            assertThat(call("POST","/tickets/"+id+"/comments",moverToken,Map.of("visibility","INTERNAL_NOTE","body","B_ONLY_NEW_NOTE"),"new-queue-note").status).isEqualTo(201);
            resume.countDown();var response=read.get(10,TimeUnit.SECONDS);
            assertThat(response.status).isEqualTo(200);
            assertThat(response.body.toString()).doesNotContain("B_ONLY_NEW_NOTE");
        } finally {resume.countDown();executor.shutdownNow();org.mockito.Mockito.reset(observedRepository);}
    }
    @Test void auditActionFilterAppliesToRowsAndTotal() throws Exception {
        String id=create("audit-filter").path("id").asText();
        assertThat(call("POST","/tickets/"+id+"/comments",aliceToken,Map.of("visibility","PUBLIC_REPLY","body","产生另一类审计事件"),"audit-comment").status).isEqualTo(201);
        var response=call("GET","/audit-events?action=TICKET_CREATED",adminToken,null,null);
        assertThat(response.status).isEqualTo(200);
        assertThat(response.body.path("total").asInt()).isEqualTo(1);
        assertThat(response.body.path("items").size()).isEqualTo(1);
        assertThat(response.body.path("items").get(0).path("action").asText()).isEqualTo("TICKET_CREATED");
    }
}
