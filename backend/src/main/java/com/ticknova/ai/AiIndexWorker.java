package com.ticknova.ai;

import com.ticknova.ai.model.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent of G3 extraction. One bounded provider batch per leased invocation. */
@Component
public class AiIndexWorker {
    private static final Logger log=LoggerFactory.getLogger(AiIndexWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AiModel model;
    private final boolean enabled;
    private final Semaphore slots=new Semaphore(2);
    public AiIndexWorker(JdbcClient jdbc,PlatformTransactionManager manager,AiModel model,@Value("${ticknova.ai.worker-enabled:true}") boolean enabled){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.model=model;this.enabled=enabled;}
    private record Chunk(UUID id,String content) {}
    private record Claim(UUID version,UUID token,String profile,int attempt,List<Chunk> chunks) {}
    @Scheduled(fixedDelayString="${ticknova.ai.index-delay-ms:1200}") public void poll(){if(enabled)try{runOnce();}catch(Exception e){log.warn("AI index polling failed type={}",e.getClass().getSimpleName());}}
    public boolean runOnce(){
        if(!slots.tryAcquire())return false;
        try {
        String profile=model.profile();Claim c=tx.execute(s->claim(profile));if(c==null)return false;
        try {
            List<float[]> vectors=model.embed(c.chunks().stream().map(Chunk::content).toList());
            if(vectors==null||vectors.size()!=c.chunks().size())throw new AiModelFailure("AI_OUTPUT_INVALID",false);
            List<String> literals=vectors.stream().map(AiText::vectorLiteral).toList();
            tx.executeWithoutResult(s->complete(c,literals));
        }catch(AiModelFailure failure){tx.executeWithoutResult(s->fail(c,failure.code(),failure.retryable()));}
        catch(Exception failure){tx.executeWithoutResult(s->fail(c,"AI_INDEX_UNAVAILABLE",true));}
        return true;
        } finally {slots.release();}
    }
    private Claim claim(String profile){
        var version=jdbc.sql("""
            SELECT v.id,v.embedding_profile,v.embedding_attempts FROM document_version v JOIN knowledge_document d ON d.id=v.document_id
            WHERE v.ingest_state='READY' AND d.publication_state<>'ARCHIVED'
              AND EXISTS(SELECT 1 FROM knowledge_chunk c WHERE c.document_version_id=v.id)
              AND (v.embedding_profile IS DISTINCT FROM :profile OR (v.embedding_state IN ('PENDING','PROCESSING')
                AND v.embedding_available_at<=now() AND (v.embedding_locked_until IS NULL OR v.embedding_locked_until<=now())))
              AND (v.embedding_locked_until IS NULL OR v.embedding_locked_until<=now())
            ORDER BY v.embedding_available_at,v.id LIMIT 1 FOR UPDATE OF v SKIP LOCKED
            """).param("profile",profile).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getInt(3)}).optional();
        if(version.isEmpty())return null;var row=version.get();UUID id=(UUID)row[0];int attempt=profile.equals(row[1])?(Integer)row[2]+1:1;
        if(attempt>5){jdbc.sql("UPDATE document_version SET embedding_state='FAILED',embedding_error_code='AI_ATTEMPTS_EXHAUSTED',embedding_lease=NULL,embedding_locked_until=NULL WHERE id=:id").param("id",id).update();return null;}
        List<Chunk> available=jdbc.sql("SELECT id,content FROM knowledge_chunk WHERE document_version_id=:id AND (embedding_profile IS DISTINCT FROM :profile OR embedding IS NULL) ORDER BY chunk_index LIMIT 32").param("id",id).param("profile",profile).query((r,n)->new Chunk(r.getObject(1,UUID.class),r.getString(2))).list();
        List<Chunk> batch=new ArrayList<>();int chars=0;
        for(Chunk chunk:available){if(chars+chunk.content().length()>64000)break;batch.add(chunk);chars+=chunk.content().length();}
        if(batch.isEmpty()){
            if(available.isEmpty())jdbc.sql("UPDATE document_version SET embedding_state='READY',embedding_profile=:p,embedding_attempts=0,embedding_error_code=NULL,embedding_lease=NULL,embedding_locked_until=NULL WHERE id=:id").param("id",id).param("p",profile).update();
            else jdbc.sql("UPDATE document_version SET embedding_state='FAILED',embedding_profile=:p,embedding_error_code='AI_INPUT_TOO_LARGE',embedding_lease=NULL,embedding_locked_until=NULL WHERE id=:id").param("id",id).param("p",profile).update();
            return null;
        }
        UUID token=UUID.randomUUID();jdbc.sql("UPDATE document_version SET embedding_state='PROCESSING',embedding_profile=:p,embedding_attempts=:a,embedding_lease=:token,embedding_locked_until=now()+interval '120 seconds',embedding_error_code=NULL WHERE id=:id").param("p",profile).param("a",attempt).param("token",token).param("id",id).update();
        return new Claim(id,token,profile,attempt,batch);
    }
    private boolean owns(Claim c){return jdbc.sql("SELECT id FROM document_version WHERE id=:id AND embedding_lease=:token AND embedding_profile=:p AND embedding_state='PROCESSING' AND embedding_locked_until>now() FOR UPDATE").param("id",c.version()).param("token",c.token()).param("p",c.profile()).query(UUID.class).optional().isPresent();}
    private void complete(Claim c,List<String> vectors){
        if(!owns(c)||!model.profile().equals(c.profile()))return;
        for(int i=0;i<c.chunks().size();i++){var chunk=c.chunks().get(i);jdbc.sql("UPDATE knowledge_chunk SET embedding=CAST(:vector AS vector),embedding_profile=:p,lexical_terms=:terms WHERE id=:id AND document_version_id=:v").param("vector",vectors.get(i)).param("p",c.profile()).param("terms",lexicalTerms(chunk.content())).param("id",chunk.id()).param("v",c.version()).update();}
        boolean ready=jdbc.sql("SELECT NOT EXISTS(SELECT 1 FROM knowledge_chunk WHERE document_version_id=:v AND (embedding_profile IS DISTINCT FROM :p OR embedding IS NULL))").param("v",c.version()).param("p",c.profile()).query(Boolean.class).single();
        jdbc.sql("UPDATE document_version SET embedding_state=:state,embedding_attempts=0,embedding_lease=NULL,embedding_locked_until=NULL,embedding_error_code=NULL,embedding_available_at=now() WHERE id=:id").param("state",ready?"READY":"PENDING").param("id",c.version()).update();
    }
    private void fail(Claim c,String code,boolean retryable){if(!owns(c))return;boolean again=retryable&&c.attempt()<5;jdbc.sql("UPDATE document_version SET embedding_state=:state,embedding_error_code=:code,embedding_lease=NULL,embedding_locked_until=NULL,embedding_available_at=now()+(:delay * interval '1 second') WHERE id=:id").param("state",again?"PENDING":"FAILED").param("code",safe(code)).param("delay",Math.min(300,2*c.attempt()*c.attempt())).param("id",c.version()).update();}
    private static String lexicalTerms(String text){
        Set<String> terms=new TreeSet<>();
        // Overlap preserves Han bigrams and bounded Latin words across tokenizer windows.
        for(int start=0;start<text.length();start+=64)terms.addAll(AiText.terms(text.substring(start,Math.min(text.length(),start+128))));
        return String.join(" ",terms);
    }
    static String safe(String code){return Set.of("AI_PROVIDER_UNAVAILABLE","AI_OUTPUT_INVALID","AI_INPUT_TOO_LARGE","AI_PROVIDER_TIMEOUT").contains(code)?code:"AI_PROVIDER_UNAVAILABLE";}
}



