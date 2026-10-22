package com.ticknova.ticket;

import com.ticknova.audit.AuditService;
import com.ticknova.catalog.CatalogService;
import com.ticknova.shared.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.ticknova.ticket.TicketDtos.*;

@Service
public class TicketService {
    private final JdbcClient jdbc;
    private final TicketRepository repo;
    private final CatalogService catalog;
    private final AuditService audit;
    public TicketService(JdbcClient jdbc,TicketRepository repo,CatalogService catalog,AuditService audit) {
        this.jdbc=jdbc;this.repo=repo;this.catalog=catalog;this.audit=audit;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(Actor actor,UUID id) {
        var row=repo.visible(actor,id);var ticket=row.summary();
        boolean process=catalog.canProcessQueue(actor,ticket.queueId());
        boolean internal=catalog.canReadQueue(actor,ticket.queueId());
        boolean publicReply=owns(actor,ticket)||process;
        return new Detail(ticket,row.description(),row.resolutionNote(),repo.comments(id,"PUBLIC_REPLY"),
            internal?repo.comments(id,"INTERNAL_NOTE"):List.of(),repo.transitions(id),new Capabilities(publicReply,process,process,process,internal),
            TicketPolicy.STATES.stream().filter(to->TicketPolicy.canTransition(actor,ticket.requesterId(),process,ticket.status(),to)).toList());
    }
    public Summary summary(Actor actor,UUID id) {return repo.visible(actor,id).summary();}
    private boolean owns(Actor actor,Summary ticket) {
        return actor.id().equals(ticket.requesterId())&&(actor.hasRole("REQUESTER")||actor.hasRole("AGENT"));
    }
    private static void changed(int rows) {
        if(rows!=1)throw new ApiException(409,"TICKET_VERSION_CONFLICT","工单已被修改，请刷新后重试");
    }
    private void checkVersion(Summary current,long expected) {
        if(current.version()!=expected)throw new ApiException(409,"TICKET_VERSION_CONFLICT","工单已被修改，请刷新后重试");
    }
    @Transactional
    public Summary create(Actor actor,Create request) {
        if(!actor.hasRole("REQUESTER")&&!actor.hasRole("AGENT"))throw new ApiException(403,"FORBIDDEN","当前角色不能创建工单");
        if(request.serviceId()!=null && catalog.services(actor,false).stream().noneMatch(s->s.id().equals(request.serviceId())))
            throw new ApiException(400,"SERVICE_UNAVAILABLE","服务目录项不存在或已停用");
        UUID id=UUID.randomUUID(),queue=catalog.generalQueueId();
        jdbc.sql("INSERT INTO ticket(id,title,description,requester_id,service_id,queue_id,priority) VALUES(:id,:title,:description,:user,:service,:queue,:priority)")
            .param("id",id).param("title",request.title().trim()).param("description",request.description()).param("user",actor.id())
            .param("service",request.serviceId()).param("queue",queue).param("priority",request.priority()==null?"NORMAL":request.priority()).update();
        audit.record(actor,"TICKET_CREATED","TICKET",id,Map.of("queueId",queue.toString()));
        return repo.get(id).summary();
    }
    @Transactional
    public Comment comment(Actor actor,UUID id,CommentRequest request) {
        var ticket=repo.visible(actor,id).summary();
        boolean process=catalog.canProcessQueue(actor,ticket.queueId());
        if(request.visibility().equals("INTERNAL_NOTE")?!process:!(owns(actor,ticket)||process))
            throw new ApiException(403,"COMMENT_FORBIDDEN","无权添加此类回复");
        UUID commentId=UUID.randomUUID();
        jdbc.sql("INSERT INTO ticket_comment(id,ticket_id,author_id,visibility,body) VALUES(:id,:ticket,:user,:visibility,:body)")
            .param("id",commentId).param("ticket",id).param("user",actor.id()).param("visibility",request.visibility()).param("body",request.body()).update();
        jdbc.sql("UPDATE ticket SET updated_at=now() WHERE id=:id").param("id",id).update();
        audit.record(actor,"TICKET_COMMENTED","TICKET",id,Map.of("visibility",request.visibility(),"commentId",commentId.toString()));
        return repo.comment(commentId);
    }
    public Comment replayComment(Actor actor,UUID id) {
        var comment=repo.comment(id);var ticket=repo.visible(actor,comment.ticketId()).summary();
        if(comment.visibility().equals("INTERNAL_NOTE")&&!catalog.canReadQueue(actor,ticket.queueId()))
            throw new ApiException(403,"COMMENT_FORBIDDEN","无权读取内部备注");
        return comment;
    }
    @Transactional
    public Summary transition(Actor actor,UUID id,TransitionRequest request,long version,UUID requestId) {
        var ticket=repo.visible(actor,id).summary();
        boolean process=catalog.canProcessQueue(actor,ticket.queueId());
        if(!TicketPolicy.canTransition(actor,ticket.requesterId(),process,ticket.status(),request.toStatus()))
            throw new ApiException(process?409:403,process?"TICKET_STATE_CONFLICT":"TRANSITION_FORBIDDEN",process?"工单不允许此状态转换":"无权执行此状态转换");
        checkVersion(ticket,version);
        String reason=request.reason()==null?"":request.reason().trim();
        boolean reopening=ticket.status().equals("CLOSED")||ticket.status().equals("RESOLVED")&&request.toStatus().equals("IN_PROGRESS");
        if((request.toStatus().equals("RESOLVED")||ticket.status().equals("CLOSED"))&&reason.isBlank())
            throw new ApiException(400,"REASON_REQUIRED","解决或管理员重开必须填写公开说明");
        String resolution=request.toStatus().equals("RESOLVED")?reason:reopening?null:repo.get(id).resolutionNote();
        changed(jdbc.sql("UPDATE ticket SET status=:status,resolution_note=:note,version=version+1,updated_at=now() WHERE id=:id AND version=:version")
            .param("status",request.toStatus()).param("note",resolution).param("id",id).param("version",version).update());
        jdbc.sql("INSERT INTO ticket_transition(id,request_id,ticket_id,actor_id,from_status,to_status,reason) VALUES(:id,:request,:ticket,:actor,:from,:to,:reason)")
            .param("id",UUID.randomUUID()).param("request",requestId).param("ticket",id).param("actor",actor.id())
            .param("from",ticket.status()).param("to",request.toStatus()).param("reason",reason).update();
        audit.record(actor,"TICKET_TRANSITIONED","TICKET",id,Map.of("from",ticket.status(),"to",request.toStatus()));
        return repo.get(id).summary();
    }
    @Transactional
    public Summary edit(Actor actor,UUID id,Edit request,long version) {
        var ticket=repo.visible(actor,id).summary();catalog.requireProcessQueue(actor,ticket.queueId());checkVersion(ticket,version);
        if(request.priority()==null&&request.tags()==null)throw new ApiException(400,"EMPTY_UPDATE","请提供优先级或标签");
        changed(jdbc.sql("UPDATE ticket SET priority=:priority,version=version+1,updated_at=now() WHERE id=:id AND version=:version")
            .param("priority",request.priority()==null?ticket.priority():request.priority()).param("id",id).param("version",version).update());
        if(request.tags()!=null) {
            Set<String> labels=new LinkedHashSet<>();
            for(String label:request.tags()) {
                String trimmed=label.trim();
                if(trimmed.isEmpty()||trimmed.length()>32)throw new ApiException(400,"INVALID_TAG","标签须为1至32字符");
                labels.add(trimmed);
            }
            jdbc.sql("DELETE FROM ticket_label WHERE ticket_id=:id").param("id",id).update();
            for(String label:labels)jdbc.sql("INSERT INTO ticket_label(ticket_id,label) VALUES(:id,:label)").param("id",id).param("label",label).update();
        }
        audit.record(actor,"TICKET_UPDATED","TICKET",id,Map.of("version",version+1));return repo.get(id).summary();
    }
    @Transactional
    public Summary assign(Actor actor,UUID id,Assignment request,long version) {
        var ticket=repo.visible(actor,id).summary();catalog.requireProcessQueue(actor,ticket.queueId());catalog.requireProcessQueue(actor,request.queueId());checkVersion(ticket,version);
        if(request.assigneeId()!=null&&!catalog.eligibleAssignee(request.queueId(),request.assigneeId()))
            throw new ApiException(400,"INVALID_ASSIGNEE","处理人必须是目标队列的有效工程师");
        changed(jdbc.sql("UPDATE ticket SET queue_id=:queue,assignee_id=:assignee,version=version+1,updated_at=now() WHERE id=:id AND version=:version")
            .param("queue",request.queueId()).param("assignee",request.assigneeId()).param("id",id).param("version",version).update());
        Map<String,Object> metadata=new HashMap<>();metadata.put("queueId",request.queueId().toString());metadata.put("assigneeId",request.assigneeId()==null?null:request.assigneeId().toString());
        audit.record(actor,"TICKET_ASSIGNED","TICKET",id,metadata);return repo.get(id).summary();
    }
}
