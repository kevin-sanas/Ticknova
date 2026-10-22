package com.ticknova.knowledge;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.Actor;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class KnowledgeWorker {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DoclingClient parser;
    private final UploadStorage storage;
    private final KnowledgeChunker chunker;
    private final AuditService audit;
    private final KnowledgeMetrics metrics;
    private final boolean enabled;
    private final int maxAttempts;
    public KnowledgeWorker(JdbcClient jdbc,PlatformTransactionManager manager,DoclingClient parser,UploadStorage storage,KnowledgeChunker chunker,AuditService audit,KnowledgeMetrics metrics,
        @Value("${ticknova.knowledge.worker-enabled:true}") boolean enabled,@Value("${ticknova.knowledge.max-attempts:5}") int maximum) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.parser=parser;this.storage=storage;this.chunker=chunker;this.audit=audit;this.metrics=metrics;this.enabled=enabled;this.maxAttempts=maximum;
        if(maximum<1||maximum>5)throw new IllegalArgumentException("Invalid job retry bound");
    }
    private record Claim(UUID event,UUID job,UUID version,UUID document,UUID actor,UUID lease,int attempt,String hash,String name,String mime,String key) {}
    @Scheduled(fixedDelayString="${ticknova.knowledge.poll-delay-ms:1000}")
    public void poll() { if(enabled)try {runOnce();} catch(Exception e){log.warn("Knowledge worker polling failed type={}",e.getClass().getSimpleName());} }
    public boolean runOnce() {
        Claim claim=tx.execute(s->claim()); if(claim==null)return false;
        String previous=MDC.get("traceId"); MDC.put("traceId",claim.job().toString());
        try {
            var parsed=metrics.measure("extracting",()->parser.convert(new DoclingClient.Source(claim.version(),claim.hash(),claim.name(),claim.mime(),storage.path(claim.key()))));
            Boolean active=tx.execute(s->{if(!ownsLease(claim))return false; jdbc.sql("UPDATE document_version SET ingest_state='CHUNKING' WHERE id=:v AND job_id=:job AND ingest_state='EXTRACTING'").param("v",claim.version()).param("job",claim.job()).update();return true;});
            if(!Boolean.TRUE.equals(active))return true;
            var parts=metrics.measure("chunking",()->chunker.split(parsed)); tx.executeWithoutResult(s->complete(claim,parsed,parts));
        } catch(IngestFailure failure) {tx.executeWithoutResult(s->fail(claim,failure.code(),failure.retryable()));}
        catch(Exception e) {tx.executeWithoutResult(s->fail(claim,"INGEST_INTERNAL_ERROR",true));}
        finally {if(previous==null)MDC.remove("traceId");else MDC.put("traceId",previous);}
        return true;
    }
    private Claim claim() {
        var row=jdbc.sql("""
            SELECT o.id,o.job_id,o.aggregate_id,o.attempts,v.document_id,v.sha256,v.file_name,v.mime_type,v.storage_key,
              v.job_id AS current_job,v.ingest_state,j.requested_by,j.status AS job_status,d.publication_state
            FROM outbox_event o JOIN ai_job j ON j.id=o.job_id JOIN document_version v ON v.id=o.aggregate_id
            JOIN knowledge_document d ON d.id=v.document_id
            WHERE o.event_type='DOCUMENT_INGEST' AND ((o.state='PENDING' AND o.available_at<=now()) OR (o.state='PROCESSING' AND o.locked_until<=now()))
            ORDER BY o.available_at,o.created_at,o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """).query((r,n)->new Object[]{r.getObject("id",UUID.class),r.getObject("job_id",UUID.class),r.getObject("aggregate_id",UUID.class),r.getInt("attempts"),r.getObject("document_id",UUID.class),r.getString("sha256"),r.getString("file_name"),r.getString("mime_type"),r.getString("storage_key"),r.getObject("current_job",UUID.class),r.getString("ingest_state"),r.getObject("requested_by",UUID.class),r.getString("job_status"),r.getString("publication_state")}).optional();
        if(row.isEmpty())return null; Object[] r=row.get();
        UUID event=(UUID)r[0],job=(UUID)r[1],version=(UUID)r[2]; int attempts=(Integer)r[3];
        if(!job.equals(r[9])||"COMPLETED".equals(r[12])||"READY".equals(r[10])) {
            jdbc.sql("UPDATE outbox_event SET state='DELIVERED',locked_until=NULL,lease_token=NULL WHERE id=:id").param("id",event).update(); return null;
        }
        if(attempts>=maxAttempts||"ARCHIVED".equals(r[13])) {
            String code=attempts>=maxAttempts?"JOB_ATTEMPTS_EXHAUSTED":"DOCUMENT_ARCHIVED";
            jdbc.sql("UPDATE outbox_event SET state='DEAD',last_error_code=:code,locked_until=NULL,lease_token=NULL WHERE id=:id").param("id",event).param("code",code).update();
            jdbc.sql("UPDATE ai_job SET status='FAILED',error_code=:code,updated_at=now() WHERE id=:id").param("id",job).param("code",code).update();
            jdbc.sql("UPDATE document_version SET ingest_state='FAILED',error_code=:code WHERE id=:v AND job_id=:job").param("v",version).param("job",job).param("code",code).update();
            audit.record(background((UUID)r[11]),"KNOWLEDGE_INGEST_FAILED","DOCUMENT_VERSION",version,Map.of("jobId",job.toString(),"errorCode",code,"attempt",attempts));return null;
        }
        UUID lease=UUID.randomUUID();int attempt=attempts+1;
        jdbc.sql("UPDATE outbox_event SET state='PROCESSING',attempts=:attempt,lease_token=:lease,locked_until=now()+interval '120 seconds' WHERE id=:id")
            .param("id",event).param("attempt",attempt).param("lease",lease).update();
        jdbc.sql("UPDATE ai_job SET status='RUNNING',attempt_count=:attempt,updated_at=now() WHERE id=:id").param("id",job).param("attempt",attempt).update();
        jdbc.sql("UPDATE document_version SET ingest_state='EXTRACTING' WHERE id=:v AND job_id=:job").param("v",version).param("job",job).update();
        return new Claim(event,job,version,(UUID)r[4],(UUID)r[11],lease,attempt,(String)r[5],(String)r[6],(String)r[7],(String)r[8]);
    }
    private boolean ownsLease(Claim c) {
        return jdbc.sql("SELECT id FROM outbox_event WHERE id=:id AND state='PROCESSING' AND lease_token=:lease AND locked_until>now() FOR UPDATE")
            .param("id",c.event()).param("lease",c.lease()).query(UUID.class).optional().isPresent();
    }
    private void complete(Claim c,DoclingClient.Parsed parsed,List<KnowledgeChunker.Part> parts) {
        if(!ownsLease(c))return;
        boolean current=jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_version v JOIN knowledge_document d ON d.id=v.document_id WHERE v.id=:v AND v.job_id=:job AND d.publication_state<>'ARCHIVED')")
            .param("v",c.version()).param("job",c.job()).query(Boolean.class).single();
        if(!current){fail(c,"DOCUMENT_ARCHIVED",false);return;}
        jdbc.sql("DELETE FROM knowledge_chunk WHERE document_version_id=:v").param("v",c.version()).update();
        for(var p:parts)jdbc.sql("INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,page_no,heading_path,content,strategy_version) VALUES(:id,:v,:index,:page,:heading,:content,:strategy)")
            .param("id",UUID.randomUUID()).param("v",c.version()).param("index",p.index()).param("page",p.pageNo()).param("heading",p.headingPath()).param("content",p.content()).param("strategy",KnowledgeChunker.STRATEGY).update();
        jdbc.sql("UPDATE document_version SET ingest_state='READY',error_code=NULL,parser_version=:parser WHERE id=:v AND job_id=:job")
            .param("parser",parsed.parserVersion()).param("v",c.version()).param("job",c.job()).update();
        jdbc.sql("UPDATE ai_job SET status='COMPLETED',error_code=NULL,result_json=jsonb_build_object('documentId',CAST(:doc AS text),'versionId',CAST(:v AS text),'chunkCount',CAST(:count AS integer)),updated_at=now() WHERE id=:id")
            .param("id",c.job()).param("doc",c.document()).param("v",c.version()).param("count",parts.size()).update();
        jdbc.sql("UPDATE outbox_event SET state='DELIVERED',locked_until=NULL,lease_token=NULL,last_error_code=NULL WHERE id=:id").param("id",c.event()).update();
        audit.record(background(c.actor()),"KNOWLEDGE_INGEST_COMPLETED","DOCUMENT_VERSION",c.version(),Map.of("jobId",c.job().toString(),"chunkCount",parts.size(),"attempt",c.attempt()));
    }
    private void fail(Claim c,String code,boolean retryable) {
        if(!ownsLease(c))return;boolean again=retryable&&c.attempt()<maxAttempts;
        long[] delays={2000,10000,60000,300000};long delay=delays[Math.min(c.attempt()-1,delays.length-1)];delay+=ThreadLocalRandom.current().nextLong(Math.max(1,delay/5));
        jdbc.sql("UPDATE outbox_event SET state=:state,last_error_code=:code,available_at=now()+(:delay * interval '1 millisecond'),locked_until=NULL,lease_token=NULL WHERE id=:id")
            .param("state",again?"PENDING":"DEAD").param("code",code).param("delay",delay).param("id",c.event()).update();
        jdbc.sql("UPDATE ai_job SET status=:status,error_code=:code,updated_at=now() WHERE id=:id")
            .param("status",again?"RETRYING":"FAILED").param("code",code).param("id",c.job()).update();
        jdbc.sql("UPDATE document_version SET ingest_state=:state,error_code=:code WHERE id=:v AND job_id=:job")
            .param("state",again?"QUEUED":"FAILED").param("code",code).param("v",c.version()).param("job",c.job()).update();
        audit.record(background(c.actor()),again?"KNOWLEDGE_INGEST_RETRY_SCHEDULED":"KNOWLEDGE_INGEST_FAILED","DOCUMENT_VERSION",c.version(),Map.of("jobId",c.job().toString(),"errorCode",code,"attempt",c.attempt()));
    }
    private static Actor background(UUID id){return new Actor(id,"","",Set.of(),false);}
}
