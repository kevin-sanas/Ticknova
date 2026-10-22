package com.ticknova.ticket;

import com.ticknova.shared.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.ticknova.ticket.TicketDtos.*;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService service;
    private final TicketRepository repo;
    private final IdempotencyService idempotency;
    public TicketController(TicketService service,TicketRepository repo,IdempotencyService idempotency) {this.service=service;this.repo=repo;this.idempotency=idempotency;}
    @GetMapping
    public PageResult<Summary> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,@RequestParam(required=false) String priority,
        @RequestParam(required=false) UUID queueId,@RequestParam(required=false) UUID assigneeId,@RequestParam(required=false) UUID serviceId,
        @RequestParam(required=false) String tag,@RequestParam(required=false) String sort,@RequestParam(defaultValue="false") boolean unassigned) {
        return repo.list(CurrentUser.get(),page,size,keyword,status,priority,queueId,assigneeId,serviceId,tag,sort,unassigned);
    }
    @GetMapping("/{id}") public Detail detail(@PathVariable UUID id) {return service.detail(CurrentUser.get(),id);}
    @PostMapping
    public ResponseEntity<Summary> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Create request) {
        var actor=CurrentUser.get();
        Summary result=idempotency.execute(actor,"CREATE_TICKET",key,request,201,requestId->{var t=service.create(actor,request);return new IdempotencyService.Created<>(t.id(),t);},id->service.summary(actor,id));
        return ResponseEntity.status(201).eTag("\""+result.version()+"\"").body(result);
    }
    @PostMapping("/{id}/comments")
    public ResponseEntity<Comment> comment(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CommentRequest request) {
        var actor=CurrentUser.get();var result=idempotency.execute(actor,"COMMENT:"+id,key,request,201,
            requestId->{var c=service.comment(actor,id,request);return new IdempotencyService.Created<>(c.id(),c);},commentId->service.replayComment(actor,commentId));
        return ResponseEntity.status(201).body(result);
    }
    @PostMapping("/{id}/transitions")
    public Summary transition(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String key,
        @RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody TransitionRequest request) {
        var actor=CurrentUser.get();long expected=version(match,request.version());
        var normalized=new TransitionRequest(request.toStatus(),request.reason(),expected);
        return idempotency.execute(actor,"TRANSITION:"+id,key,normalized,200,
            requestId->new IdempotencyService.Created<>(id,service.transition(actor,id,normalized,expected,requestId)),resourceId->service.summary(actor,resourceId));
    }
    @PatchMapping("/{id}")
    public Summary edit(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody Edit request) {
        return service.edit(CurrentUser.get(),id,request,version(match,request.version()));
    }
    @PutMapping("/{id}/assignment")
    public Summary assign(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody Assignment request) {
        return service.assign(CurrentUser.get(),id,request,version(match,request.version()));
    }
    private long version(String match,Long body) {
        Long header=null;
        if(match!=null) {
            if(!match.matches("\"[0-9]+\""))throw new ApiException(400,"INVALID_VERSION","If-Match 须为带双引号的数字版本");
            try {header=Long.parseLong(match.substring(1,match.length()-1));}catch(NumberFormatException e){throw new ApiException(400,"INVALID_VERSION","版本无效");}
        }
        if(header!=null&&body!=null&&!header.equals(body))throw new ApiException(400,"VERSION_MISMATCH","请求头与正文版本不一致");
        if(header==null&&body==null)throw new ApiException(400,"VERSION_REQUIRED","请提供工单版本");
        return header==null?body:header;
    }
}
