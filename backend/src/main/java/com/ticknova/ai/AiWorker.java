package com.ticknova.ai;

import com.ticknova.audit.AuditService;
import com.ticknova.ai.model.*;
import com.ticknova.shared.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.ticknova.ai.AiDtos.*;

@Component
public class AiWorker {
    private static final Logger log=LoggerFactory.getLogger(AiWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AiService service;
    private final AiAuthorization authorization;
    private final AiRetriever retriever;
    private final AiModel model;
    private final AiResultValidator validator;
    private final AuditService audit;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Semaphore slots=new Semaphore(2);
    public AiWorker(JdbcClient jdbc,PlatformTransactionManager manager,AiService service,AiAuthorization authorization,AiRetriever retriever,AiModel model,AiResultValidator validator,AuditService audit,ObjectMapper json,@Value("${ticknova.ai.worker-enabled:true}") boolean enabled){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.service=service;this.authorization=authorization;this.retriever=retriever;this.model=model;this.validator=validator;this.audit=audit;this.json=json;this.enabled=enabled;}
    private record Claim(UUID event,UUID job,UUID actor,UUID token,int attempt,String profile,String mode) {}
    @Scheduled(fixedDelayString="${ticknova.ai.poll-delay-ms:1000}") public void poll(){if(enabled)try{runOnce();}catch(Exception e){log.warn("AI worker polling failed type={}",e.getClass().getSimpleName());}}
    public boolean runOnce(){
        if(!slots.tryAcquire())return false;
        try{
            Claim c=tx.execute(s->claim());if(c==null)return false;
            try{
                AiService.Request request=tx.execute(s->{Actor a=authorization.actor(c.actor());var r=service.request(a,c.job());service.check(r);return r;});
                String question=request.question();String title="",description="";List<String> replies=List.of();
                if(request.ticket()!=null){var ticket=authorization.ticket(authorization.actor(c.actor()),request.ticket());title=ticket.summary().title();description=ticket.description();replies=authorization.publicReplies(request.ticket());question=AiContextBudget.retrievalQuery(title,description);}
                boolean knowledge=request.kind().equals("ANSWER")||request.kind().equals("REPLY");
                // Summary/classification are strictly public-ticket-only, even when a knowledge scope was selected.
                List<AiRetriever.Candidate> recalled=knowledge?retriever.retrieve(request.actor(),request.spaces(),question):List.of();
                List<AiModel.ServiceOption> services=request.kind().equals("CLASSIFICATION")?authorization.services():List.of();
                var prepared=AiContextBudget.prepare(request.kind(),request.question(),title,description,replies,recalled,services);
                List<AiRetriever.Candidate> candidates=prepared.sources();
                UUID suggestion=UUID.randomUUID();AiResult result;
                if(candidates.isEmpty()&&Set.of("ANSWER","REPLY").contains(request.kind()))result=validator.refusal(suggestion,request.kind(),c.mode(),c.profile());
                else {
                    // This check follows embedding/retrieval; no DB transaction spans either provider call.
                    tx.executeWithoutResult(s->{service.check(request);for(var source:candidates)authorization.citation(request.actor(),source.citation().chunkId(),request.spaces());});
                    result=validator.validate(suggestion,request.kind(),c.mode(),c.profile(),model.generate(prepared.input()),candidates,prepared.input().services());
                }
                AiResult completed=result;tx.executeWithoutResult(s->complete(c,request,completed,candidates));
            }catch(ApiException e){tx.executeWithoutResult(s->fail(c,"SOURCE_UNAVAILABLE",false));}
            catch(AiModelFailure e){tx.executeWithoutResult(s->fail(c,AiIndexWorker.safe(e.code()),e.retryable()));}
            catch(Exception e){tx.executeWithoutResult(s->fail(c,"AI_PROVIDER_UNAVAILABLE",true));}
            return true;
        }finally{slots.release();}
    }
    private Claim claim(){
        var row=jdbc.sql("""
            SELECT o.id,o.job_id,o.attempts,j.requested_by,j.status FROM outbox_event o JOIN ai_job j ON j.id=o.job_id
            WHERE o.event_type='AI_REQUEST' AND ((o.state='PENDING' AND o.available_at<=now()) OR (o.state='PROCESSING' AND o.locked_until<=now()))
            ORDER BY o.available_at,o.created_at,o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getObject(4,UUID.class),r.getString(5)}).optional();
        if(row.isEmpty())return null;var r=row.get();UUID event=(UUID)r[0],job=(UUID)r[1],actor=(UUID)r[3];int attempt=(Integer)r[2]+1;
        if(Set.of("COMPLETED","FAILED","CANCELLED").contains(r[4])){jdbc.sql("UPDATE outbox_event SET state='DELIVERED',lease_token=NULL,locked_until=NULL WHERE id=:id").param("id",event).update();return null;}
        if(attempt>5){jdbc.sql("UPDATE outbox_event SET state='DEAD',lease_token=NULL,locked_until=NULL,last_error_code='AI_ATTEMPTS_EXHAUSTED' WHERE id=:id").param("id",event).update();jdbc.sql("UPDATE ai_job SET status='FAILED',error_code='AI_ATTEMPTS_EXHAUSTED',updated_at=now() WHERE id=:id").param("id",job).update();audit.record(background(actor),"AI_REQUEST_FAILED","AI_JOB",job,Map.of("errorCode","AI_ATTEMPTS_EXHAUSTED"));return null;}
        UUID token=UUID.randomUUID();jdbc.sql("UPDATE outbox_event SET state='PROCESSING',lease_token=:token,locked_until=now()+interval '120 seconds',attempts=:attempt WHERE id=:id").param("token",token).param("attempt",attempt).param("id",event).update();jdbc.sql("UPDATE ai_job SET status='RUNNING',attempt_count=:attempt,error_code=NULL,updated_at=now() WHERE id=:id").param("attempt",attempt).param("id",job).update();
        return new Claim(event,job,actor,token,attempt,model.profile(),model.mode());
    }
    private boolean owns(Claim c){return jdbc.sql("SELECT id FROM outbox_event WHERE id=:id AND state='PROCESSING' AND lease_token=:token AND locked_until>now() FOR UPDATE").param("id",c.event()).param("token",c.token()).query(UUID.class).optional().isPresent();}
    private void complete(Claim c,AiService.Request request,AiResult result,List<AiRetriever.Candidate> context){
        if(!owns(c))return;
        if(!model.profile().equals(c.profile())){fail(c,"AI_PROVIDER_UNAVAILABLE",true);return;}
        service.lockAndCheck(request);
        for(var source:context)authorization.citation(request.actor(),source.citation().chunkId(),request.spaces());
        for(var citation:result.citations())authorization.citation(request.actor(),citation.chunkId(),request.spaces());
        if(result.serviceId()!=null&&!authorization.services().stream().anyMatch(x->x.id().equals(result.serviceId())))throw AiAuthorization.unavailable();
        jdbc.sql("INSERT INTO ai_suggestion(id,job_id,result_json) VALUES(:id,:job,CAST(:result AS jsonb))").param("id",result.suggestionId()).param("job",c.job()).param("result",json.writeValueAsString(result)).update();
        for(var source:context)jdbc.sql("INSERT INTO ai_context_source(suggestion_id,chunk_id) VALUES(:id,:chunk)").param("id",result.suggestionId()).param("chunk",source.citation().chunkId()).update();
        for(var citation:result.citations())jdbc.sql("INSERT INTO ai_citation(suggestion_id,chunk_id) VALUES(:id,:chunk)").param("id",result.suggestionId()).param("chunk",citation.chunkId()).update();
        jdbc.sql("UPDATE ai_job SET status='COMPLETED',error_code=NULL,updated_at=now() WHERE id=:id").param("id",c.job()).update();
        jdbc.sql("UPDATE outbox_event SET state='DELIVERED',lease_token=NULL,locked_until=NULL,last_error_code=NULL WHERE id=:id").param("id",c.event()).update();
        audit.record(request.actor(),"AI_REQUEST_COMPLETED","AI_JOB",c.job(),Map.of("kind",request.kind(),"outcome",result.outcome(),"attempt",c.attempt()));
    }
    private void fail(Claim c,String code,boolean retryable){
        if(!owns(c))return;boolean again=retryable&&c.attempt()<5;
        jdbc.sql("UPDATE outbox_event SET state=:state,lease_token=NULL,locked_until=NULL,last_error_code=:code,available_at=now()+(:delay * interval '1 second') WHERE id=:id").param("state",again?"PENDING":"DEAD").param("code",code).param("delay",Math.min(300,2*c.attempt()*c.attempt())).param("id",c.event()).update();
        jdbc.sql("UPDATE ai_job SET status=:state,error_code=:code,updated_at=now() WHERE id=:id").param("state",again?"RETRYING":"FAILED").param("code",code).param("id",c.job()).update();
        audit.record(background(c.actor()),again?"AI_REQUEST_RETRY_SCHEDULED":"AI_REQUEST_FAILED","AI_JOB",c.job(),Map.of("errorCode",code,"attempt",c.attempt()));
    }
    private static Actor background(UUID id){return new Actor(id,"","",Set.of(),false);}
}

