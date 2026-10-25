package com.ticknova.knowledge;

import com.ticknova.shared.Actor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"ticknova.knowledge.worker-enabled=false","ticknova.knowledge.upload-root=/tmp/ticknova-test-ingest"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class KnowledgeIngestTest {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeWorker worker;
    @Autowired MeterRegistry meters;
    @MockitoBean DoclingClient parser;
    Actor actor;
    KnowledgeDtos.Upload upload;
    @BeforeEach void fixture() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        UUID id=UUID.randomUUID(); jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'editor','Editor','unused',false)").param("id",id).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code IN ('KB_EDITOR','ADMIN')").param("id",id).update();
        actor=new Actor(id,"editor","Editor",Set.of("KB_EDITOR","ADMIN"),false);
        var space=knowledge.createSpace(actor,"Pipeline test");
        knowledge.membership(actor,space.id(),id,true,true,"Explicit editor fixture");
        upload=knowledge.upload(actor,space.id(),null,"Guide",new MockMultipartFile("file","guide.txt","text/plain","safe source".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(parser.convert(any())).thenReturn(parsed());
    }
    DoclingClient.Parsed parsed(){return new DoclingClient.Parsed("test-docling",List.of(new DoclingClient.Page(1,List.of("Guide"),"safe extracted text")));}
    String state(String table,String field,UUID id){return jdbc.sql("SELECT "+field+" FROM "+table+" WHERE id=:id").param("id",id).query(String.class).single();}
    long chunks(){return jdbc.sql("SELECT count(*) FROM knowledge_chunk WHERE document_version_id=:id").param("id",upload.versionId()).query(Long.class).single();}
    void due(){jdbc.sql("UPDATE outbox_event SET available_at=now()-interval '1 minute'").update();}

    @Test void successfulAndDuplicateDeliveryProduceOneChunkSetAndNoEmbedding() {
        assertThat(worker.runOnce()).isTrue(); assertThat(chunks()).isEqualTo(1);
        assertThat(state("document_version","ingest_state",upload.versionId())).isEqualTo("READY");
        assertThat(state("ai_job","status",upload.jobId())).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT embedding IS NULL FROM knowledge_chunk").query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE outbox_event SET state='PENDING',available_at=now(),locked_until=NULL").update();
        worker.runOnce(); assertThat(chunks()).isEqualTo(1); verify(parser,times(1)).convert(any());
    }
    @Test void transientFailureRetriesAndManualRetryKeepsFailedJobEvidence() {
        when(parser.convert(any())).thenThrow(new IngestFailure("PARSER_TIMEOUT",true));
        worker.runOnce(); assertThat(state("ai_job","status",upload.jobId())).isEqualTo("RETRYING"); assertThat(chunks()).isZero();
        doThrow(new IngestFailure("INVALID_DOCUMENT",false)).when(parser).convert(any()); due(); worker.runOnce();
        assertThat(state("document_version","ingest_state",upload.versionId())).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT state FROM outbox_event").query(String.class).single()).isEqualTo("DEAD");
        var retry=knowledge.retry(actor,upload.documentId(),upload.versionId());
        assertThat(retry.jobId()).isNotEqualTo(upload.jobId());
        doReturn(parsed()).when(parser).convert(any()); worker.runOnce();
        assertThat(state("ai_job","status",upload.jobId())).isEqualTo("FAILED");
        assertThat(state("ai_job","status",retry.jobId())).isEqualTo("COMPLETED"); assertThat(chunks()).isEqualTo(1);
    }
    @Test void expiredLeaseIsReclaimedButExhaustedLeaseBecomesDeadWithoutParserCall() {
        jdbc.sql("UPDATE outbox_event SET state='PROCESSING',attempts=1,locked_until=now()-interval '1 second',lease_token=:token").param("token",UUID.randomUUID()).update();
        worker.runOnce(); assertThat(state("ai_job","status",upload.jobId())).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT attempts FROM outbox_event").query(Integer.class).single()).isEqualTo(2);
        jdbc.sql("UPDATE document_version SET ingest_state='QUEUED'").update(); jdbc.sql("UPDATE ai_job SET status='RUNNING'").update();
        jdbc.sql("UPDATE outbox_event SET state='PROCESSING',attempts=5,locked_until=now()-interval '1 second'").update();
        worker.runOnce(); assertThat(state("ai_job","status",upload.jobId())).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT state FROM outbox_event").query(String.class).single()).isEqualTo("DEAD"); verify(parser,times(1)).convert(any());
    }
    @Test void staleWorkerCannotPublishAfterItsLeaseWasReplaced() {
        when(parser.convert(any())).thenAnswer(invocation->{jdbc.sql("UPDATE outbox_event SET lease_token=:token,locked_until=now()+interval '2 minutes'").param("token",UUID.randomUUID()).update();return parsed();});
        worker.runOnce(); assertThat(chunks()).isZero(); assertThat(state("document_version","ingest_state",upload.versionId())).isEqualTo("EXTRACTING");
    }
    @Test void chunkStatusJobAndAuditRollbackTogether() {
        jdbc.sql("CREATE OR REPLACE FUNCTION fail_ingest_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='KNOWLEDGE_INGEST_COMPLETED' THEN RAISE EXCEPTION 'forced audit failure'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER force_ingest_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION fail_ingest_audit()").update();
        try {worker.runOnce(); assertThat(chunks()).isZero(); assertThat(state("document_version","ingest_state",upload.versionId())).isEqualTo("QUEUED"); assertThat(state("ai_job","status",upload.jobId())).isEqualTo("RETRYING");}
        finally {jdbc.sql("DROP TRIGGER force_ingest_audit ON audit_event").update();jdbc.sql("DROP FUNCTION fail_ingest_audit()").update();}
    }
    @Test void concurrentWorkersClaimTheEventOnlyOnce() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(parser.convert(any())).thenAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return parsed();});
        try(var executor=Executors.newFixedThreadPool(2)) {
            Future<Boolean> first=executor.submit(worker::runOnce); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try {assertThat(executor.submit(worker::runOnce).get(5,TimeUnit.SECONDS)).isFalse();} finally {release.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
        }
        verify(parser,times(1)).convert(any());assertThat(chunks()).isEqualTo(1);
    }
    @Test void retryLimitAndOperationalMetricsReflectPersistedJobs() {
        doThrow(new IngestFailure("PARSER_TIMEOUT",true)).when(parser).convert(any());
        var timer=meters.find("ticknova.knowledge.ingest.stage").tag("stage","extracting").tag("outcome","failure").timer();long before=timer==null?0:timer.count();
        assertThat(meters.get("ticknova.knowledge.outbox.pending").gauge().value()).isEqualTo(1);
        for(int attempt=0;attempt<5;attempt++){due();worker.runOnce();}
        assertThat(state("ai_job","status",upload.jobId())).isEqualTo("FAILED");
        assertThat(meters.get("ticknova.knowledge.outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("ticknova.knowledge.outbox.dead").gauge().value()).isEqualTo(1);
        assertThat(meters.get("ticknova.knowledge.outbox.retries").gauge().value()).isEqualTo(4);
        assertThat(meters.get("ticknova.knowledge.ingest.stage").tag("stage","extracting").tag("outcome","failure").timer().count()).isEqualTo(before+5);
        verify(parser,times(5)).convert(any());
    }
}
