package com.ticknova.ai;

import com.ticknova.audit.AuditService;
import com.ticknova.ai.model.AiModel;
import com.ticknova.shared.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.ticknova.ai.AiDtos.*;

@Service
public class AiService {
    final JdbcClient jdbc;
    final AiAuthorization authorization;
    private final AiModel model;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper json;
    private final AiSnapshots snapshot;
    public AiService(JdbcClient jdbc,AiAuthorization authorization,AiModel model,IdempotencyService idempotency,AuditService audit,ObjectMapper json,PlatformTransactionManager manager){this.jdbc=jdbc;this.authorization=authorization;this.model=model;this.idempotency=idempotency;this.audit=audit;this.json=json;this.snapshot=new AiSnapshots(manager);}
    public Config config(){return new Config(model.mode(),model.profile(),!"mock".equals(model.mode()));}
    private record Fingerprint(String kind,String question,UUID ticket,List<UUID> spaces) {}
    @Transactional public Created answer(Actor actor,AnswerRequest body,String key){
        if(body==null||body.question()==null||body.question().isBlank()||body.question().length()>4000)throw new ApiException(400,"INVALID_AI_QUESTION","问题需要1至4000字符");
        return submit(actor,"ANSWER",body.question().trim(),null,body.spaceIds(),key);
    }
    @Transactional public Created draft(Actor actor,UUID ticket,DraftRequest body,String key){
        if(body==null||body.kind()==null||!Set.of("REPLY","SUMMARY","CLASSIFICATION").contains(body.kind()))throw new ApiException(400,"INVALID_AI_KIND","建议类型无效");
        authorization.ticket(authorization.actor(actor.id()),ticket);return submit(actor,body.kind(),null,ticket,body.spaceIds(),key);
    }
    private Created submit(Actor actor,String kind,String question,UUID ticket,List<UUID> selected,String key){
        authorization.actor(actor.id());
        if(selected==null||selected.stream().anyMatch(Objects::isNull)||new HashSet<>(selected).size()!=selected.size()||selected.size()>10)throw new ApiException(400,"INVALID_AI_SPACES","请选择最多10个不同空间");
        var fingerprint=new Fingerprint(kind,question,ticket,selected.stream().sorted().toList());
        return idempotency.execute(actor,"AI_REQUEST:"+kind+(ticket==null?"":":"+ticket),key,fingerprint,202,requestId->{
            List<UUID> scopes=authorization.resolve(actor,selected);UUID job=UUID.randomUUID();
            jdbc.sql("INSERT INTO ai_job(id,requested_by,job_type,subject_type,subject_id,idempotency_key) VALUES(:id,:u,'AI_REQUEST','AI_REQUEST',:id,:key)").param("id",job).param("u",actor.id()).param("key","ai:"+job).update();
            jdbc.sql("INSERT INTO ai_request(job_id,kind,question,ticket_id,space_ids) VALUES(:id,:kind,:q,:ticket,CAST(:spaces AS uuid[]))").param("id",job).param("kind",kind).param("q",question).param("ticket",ticket).param("spaces",array(scopes)).update();
            jdbc.sql("INSERT INTO outbox_event(id,job_id,event_type,aggregate_type,aggregate_id) VALUES(:id,:job,'AI_REQUEST','AI_REQUEST',:job)").param("id",UUID.randomUUID()).param("job",job).update();
            audit.record(actor,"AI_REQUESTED","AI_JOB",job,Map.of("kind",kind,"scopeCount",scopes.size()));
            return new IdempotencyService.Created<>(job,new Created(job));
        },job->{Request request=request(actor,job);check(request);return new Created(job);});
    }
    record Request(UUID job,Actor actor,String kind,String question,UUID ticket,List<UUID> spaces) {}
    Request request(Actor actor,UUID job){
        return jdbc.sql("SELECT r.*,j.requested_by FROM ai_request r JOIN ai_job j ON j.id=r.job_id WHERE j.id=:id AND j.requested_by=:u").param("id",job).param("u",actor.id()).query((r,n)->new Request(job,actor,r.getString("kind"),r.getString("question"),r.getObject("ticket_id",UUID.class),Arrays.asList((UUID[])r.getArray("space_ids").getArray()))).optional().orElseThrow(AiAuthorization::unavailable);
    }
    void check(Request request){authorization.scopes(request.actor(),request.spaces());if(request.ticket()!=null)authorization.ticket(authorization.actor(request.actor().id()),request.ticket());}
    /** Locks the same space rows as G3 writes; then rechecks at a fresh READ COMMITTED snapshot. */
    void lockAndCheck(Request request){
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR SHARE").param("id",request.actor().id()).query(UUID.class).list();
        if(!request.spaces().isEmpty())jdbc.sql("SELECT id FROM knowledge_space WHERE id IN (:ids) ORDER BY id FOR SHARE").param("ids",request.spaces()).query(UUID.class).list();
        if(request.ticket()!=null)jdbc.sql("SELECT id FROM ticket WHERE id=:id FOR SHARE").param("id",request.ticket()).query(UUID.class).list();
        check(request);
    }
    @Transactional public void cancel(Actor actor,UUID id){
        Request request=request(authorization.actor(actor.id()),id);
        // Worker claim/completion and cancellation always lock the event first. Clearing its lease
        // in this transaction makes provider calls already in flight unable to persist a result.
        jdbc.sql("SELECT id FROM outbox_event WHERE job_id=:id AND event_type='AI_REQUEST' FOR UPDATE")
            .param("id",id).query(UUID.class).optional().orElseThrow(AiAuthorization::unavailable);
        lockAndCheck(request);
        String status=jdbc.sql("SELECT status FROM ai_job WHERE id=:id FOR UPDATE").param("id",id).query(String.class).single();
        if("COMPLETED".equals(status))throw new ApiException(409,"AI_JOB_COMPLETED","任务已完成，无法取消");
        if("CANCELLED".equals(status))return;
        jdbc.sql("UPDATE outbox_event SET state='DELIVERED',lease_token=NULL,locked_until=NULL,last_error_code=NULL WHERE job_id=:id")
            .param("id",id).update();
        jdbc.sql("UPDATE ai_job SET status='CANCELLED',error_code=NULL,updated_at=now() WHERE id=:id").param("id",id).update();
        audit.record(request.actor(),"AI_REQUEST_CANCELLED","AI_JOB",id,Map.of());
    }
    public boolean isAiJob(UUID id){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_job WHERE id=:id AND job_type='AI_REQUEST')").param("id",id).query(Boolean.class).single();}
    public Job job(Actor actor,UUID id){
        Job result=snapshot.execute(status->{
            Request request=request(actor,id);
            if(!accessible(request))return null;
            var base=jdbc.sql("SELECT status,error_code,attempt_count FROM ai_job WHERE id=:id").param("id",id).query((r,n)->new Job(id,r.getString(1),r.getString(2),r.getInt(3),null)).single();
            var saved=suggestion(id);if(saved==null)return base;
            if(!validSources(request,saved)){withhold(actor,saved.id());return null;}
            return new Job(id,base.status(),base.errorCode(),base.attemptCount(),withFeedback(saved.result(),saved.feedback()));
        });
        if(result==null)throw AiAuthorization.unavailable();return result;
    }
    record Saved(UUID id,AiResult result,String feedback,boolean withheld) {}
    private Saved suggestion(UUID job){return jdbc.sql("SELECT id,result_json::text,feedback,withheld FROM ai_suggestion WHERE job_id=:id").param("id",job).query((r,n)->new Saved(r.getObject(1,UUID.class),json.readValue(r.getString(2),AiResult.class),r.getString(3),r.getBoolean(4))).optional().orElse(null);}
    private boolean accessible(Request request){try{check(request);return true;}catch(ApiException ex){var saved=suggestion(request.job());if(saved!=null)withhold(request.actor(),saved.id());return false;}}
    private boolean validSources(Request request,Saved saved){
        if(saved.withheld())return false;
        try{
            // Dependencies are chosen by the server from the complete supplied context, not by the model's citations.
            var sources=jdbc.sql("SELECT chunk_id FROM ai_context_source WHERE suggestion_id=:id ORDER BY chunk_id")
                .param("id",saved.id()).query(UUID.class).list();
            for(UUID source:sources)authorization.citation(request.actor(),source,request.spaces());
            for(var citation:saved.result().citations())authorization.citation(request.actor(),citation.chunkId(),request.spaces());
            return true;
        }catch(ApiException e){return false;}
    }
    private void withhold(Actor actor,UUID id){
        if(jdbc.sql("UPDATE ai_suggestion SET withheld=true WHERE id=:id AND NOT withheld").param("id",id).update()==1)audit.record(actor,"AI_RESULT_WITHHELD","AI_SUGGESTION",id,Map.of("errorCode","SOURCE_UNAVAILABLE"));
    }
    public void feedback(Actor actor,UUID suggestion,FeedbackRequest body){
        if(body==null||body.feedback()==null||!Set.of("ACCEPTED","REJECTED").contains(body.feedback())||(body.reason()!=null&&body.reason().length()>500))throw new ApiException(400,"INVALID_AI_FEEDBACK","反馈无效或理由超过500字符");
        Boolean ok=snapshot.execute(status->{
            UUID job=jdbc.sql("SELECT s.job_id FROM ai_suggestion s JOIN ai_job j ON j.id=s.job_id WHERE s.id=:id AND j.requested_by=:u FOR UPDATE OF s").param("id",suggestion).param("u",actor.id()).query(UUID.class).optional().orElseThrow(AiAuthorization::unavailable);
            Request request=request(actor,job);if(!accessible(request))return false;
            Saved saved=suggestion(job);if(!validSources(request,saved)){withhold(actor,suggestion);return false;}
            if(saved.feedback()!=null){if(!saved.feedback().equals(body.feedback()))throw new ApiException(409,"AI_FEEDBACK_CONFLICT","已记录不同反馈");return true;}
            jdbc.sql("UPDATE ai_suggestion SET feedback=:f,feedback_reason=:r WHERE id=:id").param("f",body.feedback()).param("r",body.reason()).param("id",suggestion).update();
            audit.record(actor,"AI_FEEDBACK_RECORDED","AI_SUGGESTION",suggestion,Map.of("feedback",body.feedback()));return true;
        });
        if(!Boolean.TRUE.equals(ok))throw AiAuthorization.unavailable();
    }
    private static AiResult withFeedback(AiResult r,String f){return new AiResult(r.suggestionId(),r.kind(),r.mode(),r.profile(),r.outcome(),r.answer(),r.summary(),r.serviceId(),r.priority(),r.tags(),r.citations(),true,f);}
    static String array(List<UUID> ids){return "{"+String.join(",",ids.stream().map(UUID::toString).toList())+"}";}
}

