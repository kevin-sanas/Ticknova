package com.ticknova.knowledge;

import com.ticknova.identity.SessionTokens;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;
import com.ticknova.shared.Actor;
import com.ticknova.shared.ApiException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "ticknova.knowledge.worker-enabled=false", "ticknova.knowledge.upload-root=/tmp/ticknova-test-uploads",
    "ticknova.knowledge.max-upload-bytes=1024"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class KnowledgeIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionTokens tokens;
    @Autowired KnowledgeService knowledge;
    @Autowired UploadStorage storage;
    @Autowired PlatformTransactionManager transactions;
    final HttpClient http=HttpClient.newHttpClient();
    UUID editor,admin,reader,other;
    String editorToken,adminToken,readerToken,otherToken;

    @BeforeEach void setup() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        editor=user("editor","KB_EDITOR"); admin=user("admin","ADMIN"); reader=user("reader","REQUESTER"); other=user("other","KB_EDITOR");
        editorToken=session(editor); adminToken=session(admin); readerToken=session(reader); otherToken=session(other);
    }

    @Test void roleAndSpaceAclAreBothRequiredAndAdminDoesNotBypassDocumentRead() throws Exception {
        assertThat(call("POST","/knowledge/spaces",readerToken,Map.of("name","private")).status).isEqualTo(403);
        String space=createSpace();
        assertThat(call("GET","/knowledge/spaces",otherToken,null).body.size()).isZero();
        assertThat(call("GET","/knowledge/spaces/"+space+"/documents",adminToken,null).status).isEqualTo(404);
        assertThat(call("GET","/knowledge/spaces/"+space+"/documents",otherToken,null).status).isEqualTo(404);
        grant(space,reader,true,false);
        assertThat(call("GET","/knowledge/spaces/"+space+"/documents",readerToken,null).status).isEqualTo(200);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",readerToken,"guide.txt","text/plain","hello").status).isEqualTo(403);
        assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+other,readerToken,Map.of("canRead",true,"canEdit",true,"reason","grant")).status).isEqualTo(403);
        assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+reader,editorToken,Map.of("canRead",true,"canEdit",true,"reason","grant")).status).isEqualTo(400);
        grant(space,other,true,false);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",otherToken,"guide.txt","text/plain","hello").status).isEqualTo(403);
    }

    @Test void uploadValidatesContentAndDeduplicatesWithinTheDocument() throws Exception {
        String space=createSpace();
        assertThat(upload("/knowledge/spaces/"+space+"/documents",editorToken,"../evil.txt","text/plain","hello").status).isEqualTo(400);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",editorToken,"fake.pdf","application/pdf","not PDF").status).isEqualTo(415);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",editorToken,"large.txt","text/plain","x".repeat(1025)).status).isEqualTo(413);
        Reply first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","hello safe world");
        assertThat(first.status).as(first.body.toString()).isEqualTo(202);
        String doc=first.body.path("documentId").asText();
        Reply duplicate=upload("/knowledge/documents/"+doc+"/versions",editorToken,"guide.txt","text/plain","hello safe world");
        assertThat(duplicate.status).isEqualTo(202);
        assertThat(duplicate.body).isEqualTo(first.body);
        assertThat(jdbc.sql("SELECT count(*) FROM document_version").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_job").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single()).isEqualTo(1);
        assertThat(call("GET","/knowledge/documents/"+doc,otherToken,null).status).isEqualTo(404);
        assertThat(call("GET","/jobs/"+first.body.path("jobId").asText(),adminToken,null).status).isEqualTo(404);
    }

    @Test void publicationKeepsOldVersionDuringIngestAndWithdrawalImmediatelyBlocksReaders() throws Exception {
        String space=createSpace(); grant(space,reader,true,false);
        Reply first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","first version");
        String doc=first.body.path("documentId").asText(), v1=first.body.path("versionId").asText();
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v1+"/publish",editorToken,null).status).isEqualTo(409);
        ready(v1,"first version");
        approve(space,doc,v1);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v1+"/publish",editorToken,null).status).isEqualTo(200);
        Reply next=upload("/knowledge/documents/"+doc+"/versions",editorToken,"guide.txt","text/plain","second version");
        String v2=next.body.path("versionId").asText();
        assertThat(call("GET","/knowledge/documents/"+doc,readerToken,null).body.path("publishedVersionId").asText()).isEqualTo(v1);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+v1+"/chunks",readerToken,null).status).isEqualTo(200);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+v2+"/chunks",readerToken,null).status).isEqualTo(404);
        ready(v2,"second version");
        approve(space,doc,v2);
        assertThat(call("POST","/knowledge/documents/"+doc+"/versions/"+v2+"/publish",editorToken,null).status).isEqualTo(200);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+v1+"/chunks",readerToken,null).status).isEqualTo(404);
        assertThat(call("POST","/knowledge/documents/"+doc+"/disable",editorToken,null).status).isEqualTo(200);
        assertThat(call("GET","/knowledge/documents/"+doc+"/versions/"+v2+"/chunks",readerToken,null).status).isEqualTo(404);
        assertThat(call("DELETE","/knowledge/documents/"+doc,editorToken,null).status).isEqualTo(204);
        assertThat(call("GET","/knowledge/documents/"+doc,editorToken,null).status).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action IN ('KNOWLEDGE_PUBLISHED','KNOWLEDGE_DISABLED','KNOWLEDGE_ARCHIVED')").query(Long.class).single()).isEqualTo(4);
    }

    @Test void retryCreatesNewEvidenceAndRevokedMembersLoseJobAccess() throws Exception {
        String space=createSpace(); grant(space,other,true,true);
        Reply first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","hello");
        String doc=first.body.path("documentId").asText(), version=first.body.path("versionId").asText(), job=first.body.path("jobId").asText();
        jdbc.sql("UPDATE document_version SET ingest_state='FAILED',error_code='PARSER_TIMEOUT' WHERE id=CAST(:id AS uuid)").param("id",version).update();
        jdbc.sql("UPDATE ai_job SET status='FAILED',error_code='PARSER_TIMEOUT' WHERE id=CAST(:id AS uuid)").param("id",job).update();
        jdbc.sql("UPDATE outbox_event SET state='DEAD' WHERE job_id=CAST(:id AS uuid)").param("id",job).update();
        String path="/knowledge/documents/"+doc+"/versions/"+version+"/retry";
        Reply retry=call("POST",path,otherToken,null), replay=call("POST",path,otherToken,null);
        assertThat(retry.status).isEqualTo(202); assertThat(replay.body).isEqualTo(retry.body);
        assertThat(retry.body.path("jobId").asText()).isNotEqualTo(job);
        assertThat(jdbc.sql("SELECT status FROM ai_job WHERE id=CAST(:id AS uuid)").param("id",job).query(String.class).single()).isEqualTo("FAILED");
        grant(space,other,false,false);
        assertThat(call("GET","/jobs/"+retry.body.path("jobId").asText(),otherToken,null).status).isEqualTo(404);
    }

    @Test void concurrentIdenticalVersionUploadsShareOneVersionAndJob() throws Exception {
        String space=createSpace();var first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","first");String doc=first.body.path("documentId").asText();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->upload("/knowledge/documents/"+doc+"/versions",editorToken,"guide.txt","text/plain","next"));
            var b=pool.submit(()->upload("/knowledge/documents/"+doc+"/versions",editorToken,"guide.txt","text/plain","next"));
            Reply one=a.get(10,TimeUnit.SECONDS),two=b.get(10,TimeUnit.SECONDS);assertThat(one.status).isEqualTo(202);assertThat(two.status).isEqualTo(202);assertThat(one.body).isEqualTo(two.body);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM document_version").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single()).isEqualTo(2);
    }
    @Test void initialCreateIdempotencySurvivesLostResponseAndReauthorizesReplay() throws Exception {
        String key=UUID.randomUUID().toString();var body=Map.of("name","Idempotent space");
        var first=callKey("POST","/knowledge/spaces",editorToken,body,key);var second=callKey("POST","/knowledge/spaces",editorToken,body,key);
        assertThat(second.body.path("id").asText()).isEqualTo(first.body.path("id").asText());
        assertThat(callKey("POST","/knowledge/spaces",editorToken,Map.of("name","different"),key).status).isEqualTo(409);
        String space=first.body.path("id").asText();key=UUID.randomUUID().toString();
        var uploadOne=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","same input",key);
        var uploadTwo=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","same input",key);
        assertThat(uploadTwo.body).isEqualTo(uploadOne.body);
        grant(space,editor,false,false);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","same input",key).status).isEqualTo(403);
    }
    @Test void uploadRollbackRemovesFileAndAllTransactionalRecords() throws Exception {
        String space=createSpace();var filePath=new AtomicReference<Path>();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var result=knowledge.upload(new Actor(editor,"editor","Editor",Set.of("KB_EDITOR"),false),UUID.fromString(space),null,"Rollback",new MockMultipartFile("file","guide.txt","text/plain","rollback content".getBytes(StandardCharsets.UTF_8)));
            String key=jdbc.sql("SELECT storage_key FROM document_version WHERE id=:id").param("id",result.versionId()).query(String.class).single();filePath.set(storage.path(key));assertThat(filePath.get()).exists();status.setRollbackOnly();
        });
        assertThat(filePath.get()).doesNotExist();
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_document").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='KNOWLEDGE_UPLOADED'").query(Long.class).single()).isZero();
    }
    @Test void uploadWaitingForSpaceLockRechecksMembershipAfterRevocationCommits() throws Exception {
        String space=createSpace();grant(space,other,true,true);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var blocker=new AtomicReference<Integer>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revocation=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                blocker.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("SELECT id FROM knowledge_space WHERE id=CAST(:id AS uuid) FOR UPDATE").param("id",space).query(UUID.class).single();
                jdbc.sql("UPDATE knowledge_space_member SET can_read=false,can_edit=false WHERE space_id=CAST(:id AS uuid) AND user_id=:user").param("id",space).param("user",other).update();
                locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("revocation release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var upload=pool.submit(()->knowledge.upload(new Actor(other,"other","Other",Set.of("KB_EDITOR"),false),UUID.fromString(space),null,"Late upload",new MockMultipartFile("file","guide.txt","text/plain","late content".getBytes(StandardCharsets.UTF_8))));
            try {
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline) {waiting=jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE :blocker=ANY(pg_blocking_pids(a.pid)))").param("blocker",blocker.get()).query(Boolean.class).single();if(waiting)break;Thread.sleep(10);}
                assertThat(waiting).as("upload must be waiting on the revocation transaction").isTrue();
            } finally {release.countDown();}
            revocation.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->upload.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e.getCause()).status()).isEqualTo(404));
        } finally {release.countDown();}
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_document").query(Long.class).single()).isZero();
    }

    void approve(String space,String document,String version) throws Exception {
        grant(space,other,true,true);
        String path="/knowledge/documents/"+document+"/versions/"+version;
        assertThat(call("POST",path+"/review-requests",editorToken,null).status).isEqualTo(200);
        assertThat(call("POST",path+"/reviews",otherToken,Map.of("decision","APPROVED")).status).isEqualTo(200);
    }
    String createSpace() throws Exception { Reply r=call("POST","/knowledge/spaces",editorToken,Map.of("name","Private manuals")); assertThat(r.status).as(r.body.toString()).isEqualTo(201); return r.body.path("id").asText(); }
    void grant(String space,UUID user,boolean read,boolean edit) throws Exception { assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+user,editorToken,Map.of("canRead",read,"canEdit",edit,"reason","test access decision")).status).isEqualTo(204); }
    void ready(String version,String content) { jdbc.sql("UPDATE document_version SET ingest_state='READY' WHERE id=CAST(:id AS uuid)").param("id",version).update(); jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,strategy_version) VALUES(:id,CAST(:v AS uuid),0,:text,'test-v1')").param("id",UUID.randomUUID()).param("v",version).param("text",content).update(); }
    UUID user(String name,String role) { UUID id=UUID.randomUUID(); jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:name,:name,'unused',false)").param("id",id).param("name",name).update(); jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code=:role").param("id",id).param("role",role).update(); return id; }
    String session(UUID id) { var t=tokens.generate(); jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:u,:hash,now()+interval '30 minutes')").param("id",UUID.randomUUID()).param("u",id).param("hash",t.hash()).update(); return t.token(); }
    Reply call(String method,String path,String token,Object body) throws Exception { return send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))); }
    Reply callKey(String method,String path,String token,Object body,String key) throws Exception {return send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));}
    Reply upload(String path,String token,String name,String mime,String content) throws Exception {
        return upload(path,token,name,mime,content,null);
    }
    Reply upload(String path,String token,String name,String mime,String content,String key) throws Exception {
        String boundary="ticknova"+UUID.randomUUID(); String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nTest guide\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+mime+"\r\n\r\n"+content+"\r\n--"+boundary+"--\r\n";
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));if(key!=null)request.header("Idempotency-Key",key);return send(request);
    }
    Reply send(HttpRequest.Builder b) throws Exception { var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString()); return new Reply(r.statusCode(),r.body().isBlank()?json.createObjectNode():json.readTree(r.body())); }
    record Reply(int status,JsonNode body) {}
}
