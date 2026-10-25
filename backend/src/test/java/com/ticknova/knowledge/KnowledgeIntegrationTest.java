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
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired com.ticknova.audit.AuditService audit;
    final HttpClient http=HttpClient.newHttpClient();
    UUID editor,admin,reader,other,reviewer;
    String editorToken,adminToken,readerToken,otherToken,reviewerToken;

    @BeforeEach void setup() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        editor=user("editor","KB_EDITOR"); admin=user("admin","ADMIN"); reader=user("reader","REQUESTER"); other=user("other","KB_EDITOR");
        editorToken=session(editor); adminToken=session(admin); readerToken=session(reader); otherToken=session(other);
        reviewer=user("reviewer","KB_REVIEWER");reviewerToken=session(reviewer);
    }

    @Test void dedicatedReviewerPermissionsAndActionsFollowCurrentRoleAclAndExactVersion() throws Exception {
        assertThat(call("POST","/knowledge/spaces",editorToken,Map.of("name","denied")).status).isEqualTo(403);
        Reply created=call("POST","/knowledge/spaces",adminToken,Map.of("name","Managed"));
        assertThat(created.status).isEqualTo(201);
        assertThat(created.body.path("canRead").asBoolean()).isFalse();
        assertThat(created.body.path("canEdit").asBoolean()).isFalse();
        String space=created.body.path("id").asText();grant(space,editor,true,true);
        assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+reviewer,adminToken,Map.of("canRead",true,"canEdit",true,"canReview",true,"reason","invalid")).status).isEqualTo(400);
        grantReview(space,reviewer,true,true);
        assertThat(call("GET","/knowledge/spaces/"+space+"/members",adminToken,null).body.toString()).contains("KB_REVIEWER","canReview");
        assertThat(call("GET","/knowledge/spaces/"+space+"/member-candidates",adminToken,null).body.toString()).contains("KB_REVIEWER","roles");
        Reply uploaded=upload("/knowledge/spaces/"+space+"/documents",editorToken,"a.txt","text/plain","first");
        String doc=uploaded.body.path("documentId").asText(),v1=uploaded.body.path("versionId").asText(),base="/knowledge/documents/"+doc;
        ready(v1,"first");
        assertThat(call("GET",base,reviewerToken,null).status).isEqualTo(404);
        assertThat(call("POST",base+"/versions/"+v1+"/review-requests",editorToken,null).status).isEqualTo(200);
        Reply pending=call("GET",base,reviewerToken,null);
        assertThat(pending.status).isEqualTo(200);
        assertThat(pending.body.path("versions").get(0).path("allowedActions").toString()).contains("APPROVE","REJECT").doesNotContain("PUBLISH","EDIT_DRAFT");
        assertThat(call("GET",base+"/versions/"+v1+"/chunks",reviewerToken,null).status).isEqualTo(200);
        assertThat(call("POST",base+"/versions/"+v1+"/reviews",editorToken,Map.of("decision","APPROVED")).status).isEqualTo(403);
        assertThat(call("POST",base+"/versions/"+v1+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status).isEqualTo(200);
        assertThat(call("POST",base+"/versions/"+v1+"/publish",reviewerToken,null).status).isEqualTo(403);
        assertThat(call("POST",base+"/versions/"+v1+"/publish",editorToken,null).status).isEqualTo(200);
        Reply next=upload(base+"/versions",editorToken,"b.txt","text/plain","private");String v2=next.body.path("versionId").asText();ready(v2,"private");
        assertThat(call("GET",base+"/versions/"+v2+"/chunks",reviewerToken,null).status).isEqualTo(404);
        assertThat(call("GET",base+"/versions/"+v1+"/chunks",reviewerToken,null).status).isEqualTo(200);
        assertThat(call("POST",base+"/versions/"+v2+"/review-requests",editorToken,null).status).isEqualTo(200);
        grantReview(space,reviewer,true,false);
        assertThat(call("POST",base+"/versions/"+v2+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status).isEqualTo(404);
        grantReview(space,reviewer,true,true);
        assertThat(call("PUT","/users/"+reviewer+"/roles",adminToken,Map.of("roles",List.of("REQUESTER"))).status).isEqualTo(204);
        assertThat(call("POST",base+"/versions/"+v2+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status).isEqualTo(403);
    }

    @Test void reviewerCannotReadSupersededPrivateVersionsOrReviewOwnVersionAndDisabledActorFails() throws Exception {
        String space=createSpace();grantReview(space,reviewer,true,true);
        Reply first=upload("/knowledge/spaces/"+space+"/documents",editorToken,"old.txt","text/plain","old private");
        String doc=first.body.path("documentId").asText(),v1=first.body.path("versionId").asText(),base="/knowledge/documents/"+doc;
        ready(v1,"old private");assertThat(call("POST",base+"/versions/"+v1+"/review-requests",editorToken,null).status).isEqualTo(200);
        Reply second=upload(base+"/versions",editorToken,"new.txt","text/plain","new private");String v2=second.body.path("versionId").asText();ready(v2,"new private");
        assertThat(call("GET",base+"/versions/"+v1+"/chunks",reviewerToken,null).status).isEqualTo(404);
        assertThat(call("GET","/knowledge/review-tasks",reviewerToken,null).body.path("total").asLong()).isZero();
        assertThat(call("POST",base+"/versions/"+v2+"/review-requests",editorToken,null).status).isEqualTo(200);
        assertThat(call("PUT","/users/"+editor+"/roles",adminToken,Map.of("roles",List.of("KB_EDITOR","KB_REVIEWER","ADMIN"))).status).isEqualTo(204);
        grantReview(space,editor,true,true);
        assertThat(call("GET",base,editorToken,null).status).isEqualTo(404);
        assertThat(call("POST",base+"/versions/"+v2+"/reviews",editorToken,Map.of("decision","APPROVED")).status).isEqualTo(404);
        assertThat(upload(base+"/versions",editorToken,"denied.txt","text/plain","denied").status).isEqualTo(404);
        var captured=new Actor(reviewer,"reviewer","Reviewer",Set.of("KB_REVIEWER"),false);
        assertThat(call("PATCH","/users/"+reviewer,adminToken,Map.of("active",false)).status).isEqualTo(200);
        assertThat(call("GET",base,reviewerToken,null).status).isEqualTo(401);
        assertThatThrownBy(()->knowledge.review(captured,UUID.fromString(doc),UUID.fromString(v2),"APPROVED",null)).isInstanceOf(ApiException.class)
            .satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(403));
    }

    @Test void demoSeedLeavesNonEmptyDatabasePasswordsAndHistoryUnchanged() {
        var hashes=jdbc.sql("SELECT password_hash FROM app_user ORDER BY id").query(String.class).list();
        long events=jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single();
        new TransactionTemplate(transactions).executeWithoutResult(status->new com.ticknova.identity.DemoSeed(jdbc,passwords,audit,UUID.randomUUID().toString()).run(new org.springframework.boot.DefaultApplicationArguments()));
        assertThat(jdbc.sql("SELECT password_hash FROM app_user ORDER BY id").query(String.class).list()).isEqualTo(hashes);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isEqualTo(events);
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
        assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+reader,adminToken,Map.of("canRead",true,"canEdit",true,"reason","grant")).status).isEqualTo(400);
        grant(space,other,true,false);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",otherToken,"guide.txt","text/plain","hello").status).isEqualTo(403);
    }

    @Test void revokedAdminCannotEnumerateOrManageAFormerlyCreatedSpace() throws Exception {
        Reply created=call("POST","/knowledge/spaces",adminToken,Map.of("name","Revocation boundary"));
        assertThat(created.status).isEqualTo(201);
        String space=created.body.path("id").asText();
        assertThat(call("GET","/knowledge/spaces",adminToken,null).body.toString()).contains(space);

        jdbc.sql("DELETE FROM user_role WHERE user_id=:user AND role_id=(SELECT id FROM app_role WHERE code='ADMIN')")
            .param("user",admin).update();

        assertThat(call("GET","/knowledge/spaces",adminToken,null).body.toString()).doesNotContain(space);
        assertThat(call("GET","/knowledge/spaces/"+space+"/members",adminToken,null).status).isEqualTo(403);
        var capturedAdmin=new Actor(admin,"admin","admin",Set.of("ADMIN"),false);
        assertThatThrownBy(()->knowledge.members(capturedAdmin,UUID.fromString(space))).isInstanceOf(ApiException.class)
            .satisfies(error->assertThat(((ApiException)error).status()).isEqualTo(404));

        jdbc.sql("INSERT INTO user_role(user_id,role_id) SELECT :user,id FROM app_role WHERE code='ADMIN'")
            .param("user",admin).update();
        assertThat(call("GET","/knowledge/spaces",adminToken,null).body.toString()).contains(space);
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
        var first=callKey("POST","/knowledge/spaces",adminToken,body,key);var second=callKey("POST","/knowledge/spaces",adminToken,body,key);
        assertThat(second.body.path("id").asText()).isEqualTo(first.body.path("id").asText());
        assertThat(callKey("POST","/knowledge/spaces",adminToken,Map.of("name","different"),key).status).isEqualTo(409);
        String space=first.body.path("id").asText();grant(space,editor,true,true);key=UUID.randomUUID().toString();
        var uploadOne=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","same input",key);
        var uploadTwo=upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","same input",key);
        assertThat(uploadTwo.body).isEqualTo(uploadOne.body);
        grant(space,editor,false,false);
        assertThat(upload("/knowledge/spaces/"+space+"/documents",editorToken,"guide.txt","text/plain","same input",key).status).isEqualTo(404);
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

    @Test void reviewWaitingForSpaceLockRechecksRevokedReviewerAclBeforeMutation() throws Exception {
        String space=createSpace();grantReview(space,reviewer,true,true);
        Reply upload=upload("/knowledge/spaces/"+space+"/documents",editorToken,"review.txt","text/plain","locked review");
        UUID doc=UUID.fromString(upload.body.path("documentId").asText()),version=UUID.fromString(upload.body.path("versionId").asText());
        ready(version.toString(),"locked review");knowledge.requestReview(new Actor(editor,"editor","Editor",Set.of("KB_EDITOR"),false),doc,version);
        var captured=new Actor(reviewer,"reviewer","Reviewer",Set.of("KB_REVIEWER"),false);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var blocker=new AtomicReference<Integer>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                blocker.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("SELECT id FROM knowledge_space WHERE id=CAST(:id AS uuid) FOR UPDATE").param("id",space).query(UUID.class).single();
                jdbc.sql("UPDATE knowledge_space_member SET can_read=false,can_review=false WHERE space_id=CAST(:id AS uuid) AND user_id=:user").param("id",space).param("user",reviewer).update();
                locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("revocation release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var review=pool.submit(()->knowledge.review(captured,doc,version,"APPROVED",null));
            try {
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline){waiting=jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE :blocker=ANY(pg_blocking_pids(a.pid)))").param("blocker",blocker.get()).query(Boolean.class).single();if(waiting)break;Thread.sleep(10);}
                assertThat(waiting).as("review must wait on the ACL transaction").isTrue();
            }finally{release.countDown();}
            revoke.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->review.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApiException.class)
                .satisfies(e->assertThat(((ApiException)e.getCause()).status()).isEqualTo(404));
        }finally{release.countDown();}
        assertThat(jdbc.sql("SELECT review_state FROM document_version WHERE id=:id").param("id",version).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='KNOWLEDGE_REVIEWED'").query(Long.class).single()).isZero();
    }

    void approve(String space,String document,String version) throws Exception {
        grantReview(space,reviewer,true,true);
        String path="/knowledge/documents/"+document+"/versions/"+version;
        assertThat(call("POST",path+"/review-requests",editorToken,null).status).isEqualTo(200);
        assertThat(call("POST",path+"/reviews",reviewerToken,Map.of("decision","APPROVED")).status).isEqualTo(200);
    }
    String createSpace() throws Exception { Reply r=call("POST","/knowledge/spaces",adminToken,Map.of("name","Private manuals")); assertThat(r.status).as(r.body.toString()).isEqualTo(201);String id=r.body.path("id").asText();grant(id,editor,true,true);return id; }
    void grant(String space,UUID user,boolean read,boolean edit) throws Exception { assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+user,adminToken,Map.of("canRead",read,"canEdit",edit,"canReview",false,"reason","test access decision")).status).isEqualTo(204); }
    void grantReview(String space,UUID user,boolean read,boolean review) throws Exception { assertThat(call("PUT","/knowledge/spaces/"+space+"/members/"+user,adminToken,Map.of("canRead",read,"canEdit",false,"canReview",review,"reason","test review access decision")).status).isEqualTo(204); }
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
