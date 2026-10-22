package com.ticknova.workspace;

import com.ticknova.ai.*;
import com.ticknova.ai.model.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ticknova.ai.worker-enabled=false","ticknova.knowledge.worker-enabled=false"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class WorkspaceIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.ticknova.identity.SessionTokens tokens;
    @Autowired AiWorker worker;
    @MockitoBean AiModel model;
    final HttpClient http=HttpClient.newHttpClient();
    final UUID queue=UUID.fromString("20000000-0000-0000-0000-000000000001");
    UUID reader,other,admin,agent,reviewer,space,hiddenSpace,document,hiddenDocument,ticket,otherTicket;
    String readerToken,otherToken,adminToken,agentToken;
    record Reply(int status,JsonNode body) {}
    @BeforeEach void setup(){
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        jdbc.sql("UPDATE support_queue SET active=true WHERE id=:id").param("id",queue).update();
        reader=user("portal-reader","REQUESTER");other=user("portal-other","REQUESTER");admin=user("portal-admin","ADMIN");agent=user("portal-agent","AGENT");reviewer=user("portal-reviewer","KB_EDITOR");
        readerToken=session(reader);otherToken=session(other);adminToken=session(admin);agentToken=session(agent);
        jdbc.sql("INSERT INTO queue_member(queue_id,user_id) VALUES(:q,:u)").param("q",queue).param("u",agent).update();
        space=space(reader);hiddenSpace=space(other);document=document(space,reader,"Visible guide","uniquealpha public body");hiddenDocument=document(hiddenSpace,other,"Hidden secret","uniquealpha private secret");
        ticket=ticket(reader,null);otherTicket=ticket(other,agent);
        when(model.profile()).thenReturn("workspace:1024");when(model.mode()).thenReturn("mock");
        when(model.embed(anyList())).thenAnswer(c->{List<String> values=c.getArgument(0);return values.stream().map(x->{float[] v=new float[1024];v[0]=1;return v;}).toList();});
        when(model.generate(any())).thenReturn(new AiModel.Output(null,"Public summary",null,null,List.of(),List.of()));
    }
    @Test void portalFiltersBeforeSearchSnippetAndPopularIncludingAdmin() throws Exception {
        Reply r=call("GET","/workspace/portal?q=uniquealpha",readerToken,null);
        assertThat(r.status).as(r.body.toString()).isEqualTo(200);assertThat(r.body.path("knowledge").size()).isEqualTo(1);
        assertThat(r.body.toString()).contains("Visible guide","public body").doesNotContain("Hidden secret","private secret");
        assertThat(r.body.path("popularDocuments").size()).isEqualTo(1);assertThat(r.body.path("popularDocuments").get(0).path("viewCount").asLong()).isZero();
        assertThat(call("GET","/workspace/portal?q=uniquealpha",adminToken,null).body.path("knowledge").isEmpty()).isTrue();
        assertThat(call("GET","/workspace/portal?q="+"x".repeat(257),readerToken,null).status).isEqualTo(400);
        jdbc.sql("UPDATE knowledge_document SET publication_state='DISABLED',published_version_id=NULL WHERE id=:id").param("id",document).update();
        assertThat(call("GET","/workspace/portal?q=uniquealpha",readerToken,null).body.path("knowledge").isEmpty()).isTrue();
    }
    @Test void unapprovedPublishedPointerCannotSurfaceAsPortalContentOrReceiveViews() throws Exception {
        jdbc.sql("UPDATE document_version SET review_state='REJECTED',review_reason='Fixture review rejection' WHERE id=(SELECT published_version_id FROM knowledge_document WHERE id=:d)")
            .param("d",document).update();
        Reply p=call("GET","/workspace/portal?q=uniquealpha",readerToken,null);
        assertThat(p.status).isEqualTo(200);assertThat(p.body.path("knowledge").isEmpty()).isTrue();assertThat(p.body.path("popularDocuments").isEmpty()).isTrue();
        assertThat(call("POST","/knowledge/documents/"+document+"/views",readerToken,null).status).isEqualTo(404);
        assertThat(call("GET","/workspace/analytics",adminToken,null).body.path("knowledge").path("publishedDocuments").asLong()).isEqualTo(1);
    }
    @Test void portalTicketsAndUnassignedKeepActorScope() throws Exception {
        Reply own=call("GET","/workspace/portal",readerToken,null);assertThat(own.status).isEqualTo(200);
        assertThat(own.body.path("tickets").size()).isEqualTo(1);assertThat(own.body.path("tickets").get(0).path("id").asText()).isEqualTo(ticket.toString());
        assertThat(call("GET","/workspace/portal",adminToken,null).body.path("tickets").isEmpty()).isTrue();
        Reply all=call("GET","/tickets?unassigned=true",agentToken,null);assertThat(all.status).isEqualTo(200);assertThat(all.body.path("items").size()).isEqualTo(1);
        assertThat(all.body.path("items").get(0).path("id").asText()).isEqualTo(ticket.toString());
        assertThat(call("GET","/tickets?unassigned=true",otherToken,null).body.path("items").isEmpty()).isTrue();
    }
    @Test void unassignedFilterCannotExpandQueueOrRequesterScope() throws Exception {
        Reply scoped=call("GET","/tickets?unassigned=true",agentToken,null);
        assertThat(scoped.status).isEqualTo(200);assertThat(scoped.body.path("items").size()).isEqualTo(1);
        assertThat(scoped.body.path("items").get(0).path("id").asText()).isEqualTo(ticket.toString());
        assertThat(call("GET","/tickets?unassigned=true",otherToken,null).body.path("items").isEmpty()).isTrue();
        assertThat(call("GET","/tickets?unassigned=true",adminToken,null).body.path("items").isEmpty()).isTrue();
        assertThat(call("GET","/tickets",agentToken,null).body.path("items").size()).isEqualTo(2);
        assertThat(call("GET","/tickets?unassigned=true&assigneeId="+agent,agentToken,null).body.path("items").isEmpty()).isTrue();
    }
    @Test void viewsAreUniquePerAuthorizedUserUtcDayAndPopularUsesRealCounts() throws Exception {
        assertThat(call("POST","/knowledge/documents/"+document+"/views",readerToken,null).status).isEqualTo(204);
        assertThat(call("POST","/knowledge/documents/"+document+"/views",readerToken,null).status).isEqualTo(204);
        assertThat(call("POST","/knowledge/documents/"+document+"/views",adminToken,null).status).isEqualTo(404);
        Reply p=call("GET","/workspace/portal",readerToken,null);assertThat(p.body.path("popularDocuments").get(0).path("viewCount").asLong()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT view_date FROM knowledge_document_view WHERE document_id=:d").param("d",document).query(LocalDate.class).single()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        jdbc.sql("UPDATE knowledge_space_member SET can_read=false WHERE space_id=:s AND user_id=:u").param("s",space).param("u",reader).update();
        assertThat(call("POST","/knowledge/documents/"+document+"/views",readerToken,null).status).isEqualTo(404);
    }
    @Test void categoriesOnlyComeFromReadableActiveSpaces() throws Exception {
        assertThat(call("GET","/workspace/portal",readerToken,null).status).isEqualTo(200);
        UUID category=UUID.randomUUID(),secret=UUID.randomUUID();
        jdbc.sql("INSERT INTO knowledge_category(id,space_id,name) VALUES(:id,:s,'Visible category')").param("id",category).param("s",space).update();
        jdbc.sql("INSERT INTO knowledge_category(id,space_id,name) VALUES(:id,:s,'Hidden category')").param("id",secret).param("s",hiddenSpace).update();
        Reply p=call("GET","/workspace/portal",readerToken,null);assertThat(p.status).isEqualTo(200);assertThat(p.body.path("categories").size()).isEqualTo(1);assertThat(p.body.toString()).contains("Visible category").doesNotContain("Hidden category");
    }
    @Test void analyticsIsAdminOnlyUsesInclusiveUtcDatesAndNoBusinessText() throws Exception {
        assertThat(call("GET","/workspace/analytics",readerToken,null).status).isEqualTo(403);
        jdbc.sql("UPDATE ticket SET created_at='2026-01-02T00:00:00Z' WHERE id=:id").param("id",ticket).update();
        jdbc.sql("INSERT INTO ticket_transition(id,request_id,ticket_id,actor_id,from_status,to_status,created_at) VALUES(:id,:r,:t,:u,'IN_PROGRESS','RESOLVED','2026-01-03T23:59:59Z')").param("id",UUID.randomUUID()).param("r",UUID.randomUUID()).param("t",ticket).param("u",agent).update();
        Reply r=call("GET","/workspace/analytics?from=2026-01-01&to=2026-01-03",adminToken,null);assertThat(r.status).as(r.body.toString()).isEqualTo(200);
        assertThat(r.body.path("ticketTotal").asLong()).isEqualTo(1);assertThat(r.body.path("daily").size()).isEqualTo(3);
        assertThat(r.body.path("daily").get(0).path("created").asLong()).isZero();assertThat(r.body.path("daily").get(1).path("created").asLong()).isEqualTo(1);assertThat(r.body.path("daily").get(2).path("resolved").asLong()).isEqualTo(1);
        assertThat(r.body.path("knowledge").path("publishedDocuments").asLong()).isEqualTo(2);assertThat(r.body.toString()).doesNotContain("Public ticket","Visible guide","private secret");
        for(String range:List.of("from=2026-01-03&to=2026-01-01","from=nope","from=2020-01-01&to=2026-01-01"))assertThat(call("GET","/workspace/analytics?"+range,adminToken,null).status).isEqualTo(400);
    }
    @ParameterizedTest
    @ValueSource(strings={"0000-01-01","+10000-01-01","+999999999-01-01"})
    void analyticsRejectsNonFourDigitPositiveBusinessYears(String date) throws Exception {
        String encoded=URLEncoder.encode(date,StandardCharsets.UTF_8);
        Reply r=call("GET","/workspace/analytics?from="+encoded+"&to="+encoded,adminToken,null);
        assertThat(r.status).as(date+" "+r.body).isEqualTo(400);
        assertThat(r.body.path("code").asText()).isEqualTo("INVALID_ANALYTICS_RANGE");
    }
    @ParameterizedTest
    @ValueSource(strings={"0001-01-01","9999-12-31"})
    void analyticsAcceptsFourDigitYearBounds(String date) throws Exception {
        Reply r=call("GET","/workspace/analytics?from="+date+"&to="+date,adminToken,null);
        assertThat(r.status).as(date+" "+r.body).isEqualTo(200);
        assertThat(r.body.path("from").asText()).isEqualTo(date);assertThat(r.body.path("to").asText()).isEqualTo(date);
        assertThat(r.body.path("daily").size()).isEqualTo(1);
    }
    @Test void analyticsCountsRealAiCompletionRefusalAndFeedback() throws Exception {
        assertThat(call("GET","/workspace/analytics",adminToken,null).status).isEqualTo(200);
        UUID job=ask();worker.runOnce();Reply saved=call("GET","/jobs/"+job,readerToken,null);
        UUID suggestion=UUID.fromString(saved.body.path("result").path("suggestionId").asText());
        assertThat(call("POST","/ai/suggestions/"+suggestion+"/feedback",readerToken,Map.of("feedback","ACCEPTED")).status).isEqualTo(204);
        Reply analytics=call("GET","/workspace/analytics",adminToken,null);JsonNode ai=analytics.body.path("ai");
        assertThat(ai.path("completed").asLong()).isEqualTo(1);assertThat(ai.path("refused").asLong()).isEqualTo(1);
        assertThat(ai.path("accepted").asLong()).isEqualTo(1);assertThat(ai.path("rejected").asLong()).isZero();
    }
    @Test void cancelIsCreatorOnlyIdempotentAndFencesQueuedWorker() throws Exception {
        UUID job=ask();assertThat(call("POST","/ai/jobs/"+job+"/cancel",otherToken,null).status).isEqualTo(404);
        assertThat(call("POST","/ai/jobs/"+job+"/cancel",readerToken,null).status).isEqualTo(204);assertThat(call("POST","/ai/jobs/"+job+"/cancel",readerToken,null).status).isEqualTo(204);
        worker.runOnce();assertThat(call("GET","/jobs/"+job,readerToken,null).body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_suggestion WHERE job_id=:id").param("id",job).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_REQUEST_CANCELLED' AND subject_id=:id").param("id",job).query(Long.class).single()).isEqualTo(1);
    }
    @Test void completedJobCannotBeCancelledAndScopeRevocationBlocksCancellation() throws Exception {
        UUID job=ask();worker.runOnce();assertThat(call("POST","/ai/jobs/"+job+"/cancel",readerToken,null).status).isEqualTo(409);
        UUID second=ask();jdbc.sql("UPDATE knowledge_space_member SET can_read=false WHERE space_id=:s AND user_id=:u").param("s",space).param("u",reader).update();
        assertThat(call("POST","/ai/jobs/"+second+"/cancel",readerToken,null).status).isEqualTo(404);
    }
    @Test void cancellationDuringProviderCallCannotCommitResultOrSuccessAudit() throws Exception {
        Reply created=call("POST","/tickets/"+ticket+"/ai-drafts",readerToken,Map.of("kind","SUMMARY","spaceIds",List.of()));UUID job=UUID.fromString(created.body.path("jobId").asText());
        when(model.generate(any())).thenAnswer(c->{assertThat(call("POST","/ai/jobs/"+job+"/cancel",readerToken,null).status).isEqualTo(204);return new AiModel.Output(null,"Public summary",null,null,List.of(),List.of());});
        assertThat(worker.runOnce()).isTrue();assertThat(call("GET","/jobs/"+job,readerToken,null).body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_suggestion WHERE job_id=:id").param("id",job).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='AI_REQUEST_COMPLETED' AND subject_id=:id").param("id",job).query(Long.class).single()).isZero();
    }
    @Test void cancelledProviderExceptionCannotScheduleRetryOrFailureAudit() throws Exception {
        Reply created=call("POST","/tickets/"+ticket+"/ai-drafts",readerToken,Map.of("kind","SUMMARY","spaceIds",List.of()));
        UUID job=UUID.fromString(created.body.path("jobId").asText());
        when(model.generate(any())).thenAnswer(c->{
            assertThat(call("POST","/ai/jobs/"+job+"/cancel",readerToken,null).status).isEqualTo(204);
            throw new AiModelFailure("AI_PROVIDER_UNAVAILABLE",true);
        });
        assertThat(worker.runOnce()).isTrue();assertCancelledWithoutWorkerOutcome(job);
        assertThat(worker.runOnce()).isFalse();
    }
    @Test void cancelledExpiredLeaseCannotBeReclaimedOrCommittedByItsStaleOwner() throws Exception {
        Reply created=call("POST","/tickets/"+ticket+"/ai-drafts",readerToken,Map.of("kind","SUMMARY","spaceIds",List.of()));
        UUID job=UUID.fromString(created.body.path("jobId").asText());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(model.generate(any())).thenAnswer(c->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return new AiModel.Output(null,"Public summary",null,null,List.of(),List.of());});
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> stale=pool.submit(()->worker.runOnce());assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE outbox_event SET locked_until=now()-interval '1 second' WHERE job_id=:id").param("id",job).update();
            assertThat(call("POST","/ai/jobs/"+job+"/cancel",readerToken,null).status).isEqualTo(204);
            assertThat(worker.runOnce()).isFalse();release.countDown();assertThat(stale.get(10,TimeUnit.SECONDS)).isTrue();
            assertCancelledWithoutWorkerOutcome(job);
        }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    void assertCancelledWithoutWorkerOutcome(UUID job) throws Exception {
        assertThat(call("GET","/jobs/"+job,readerToken,null).body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT state='DELIVERED' AND lease_token IS NULL AND locked_until IS NULL AND attempts=1 FROM outbox_event WHERE job_id=:id").param("id",job).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_suggestion WHERE job_id=:id").param("id",job).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE subject_id=:id AND action IN ('AI_REQUEST_COMPLETED','AI_REQUEST_FAILED','AI_REQUEST_RETRY_SCHEDULED')").param("id",job).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE subject_id=:id AND action='AI_REQUEST_CANCELLED'").param("id",job).query(Long.class).single()).isEqualTo(1);
    }
    UUID ask() throws Exception {Reply r=call("POST","/ai/answers",readerToken,Map.of("question","No evidence","spaceIds",List.of()));assertThat(r.status).isEqualTo(202);return UUID.fromString(r.body.path("jobId").asText());}
    UUID user(String name,String role){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:n,:n,'unused',false)").param("id",id).param("n",name).update();jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code=:r").param("id",id).param("r",role).update();return id;}
    String session(UUID id){var t=tokens.generate();jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:u,:h,now()+interval '30 minutes')").param("id",UUID.randomUUID()).param("u",id).param("h",t.hash()).update();return t.token();}
    UUID space(UUID user){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES(:id,'Workspace test',:u)").param("id",id).param("u",user).update();jdbc.sql("INSERT INTO knowledge_space_member(space_id,user_id,can_read) VALUES(:id,:u,true)").param("id",id).param("u",user).update();return id;}
    UUID document(UUID s,UUID u,String title,String body){UUID d=UUID.randomUUID(),v=UUID.randomUUID();jdbc.sql("INSERT INTO knowledge_document(id,space_id,title,created_by) VALUES(:d,:s,:t,:u)").param("d",d).param("s",s).param("t",title).param("u",u).update();jdbc.sql("INSERT INTO document_version(id,document_id,version_no,file_name,mime_type,sha256,storage_key,file_size,created_by,ingest_state,publication_state,review_state,reviewer_id,reviewed_at) VALUES(:v,:d,1,'guide.txt','text/plain',:hash,:key,100,:u,'READY','PUBLISHED','APPROVED',:reviewer,now())").param("v",v).param("d",d).param("hash","a".repeat(64)).param("key",v.toString()).param("u",u).param("reviewer",reviewer).update();jdbc.sql("UPDATE knowledge_document SET latest_version_id=:v,published_version_id=:v,publication_state='PUBLISHED' WHERE id=:d").param("v",v).param("d",d).update();jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,strategy_version) VALUES(:id,:v,0,:body,'test')").param("id",UUID.randomUUID()).param("v",v).param("body",body).update();return d;}
    UUID ticket(UUID user,UUID assignee){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO ticket(id,title,description,requester_id,queue_id,assignee_id) VALUES(:id,'Public ticket','Public description',:u,:q,:a)").param("id",id).param("u",user).param("q",queue).param("a",assignee).update();return id;}
    Reply call(String method,String path,String token,Object body) throws Exception {var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString());b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Reply(r.statusCode(),r.body().isBlank()?json.createObjectNode():json.readTree(r.body()));}
}







