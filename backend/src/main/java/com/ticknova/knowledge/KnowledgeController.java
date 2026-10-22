package com.ticknova.knowledge;

import com.ticknova.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.ticknova.knowledge.KnowledgeDtos.*;

@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    public KnowledgeController(KnowledgeService knowledge) {this.knowledge=knowledge;}
    @GetMapping("/knowledge/spaces") public List<Space> spaces(){return knowledge.spaces(CurrentUser.get());}
    @PostMapping("/knowledge/spaces") @ResponseStatus(HttpStatus.CREATED)
    public Space create(@Valid @RequestBody CreateSpace body,@RequestHeader(value="Idempotency-Key",required=false) String key){return knowledge.createSpace(CurrentUser.get(),body.name(),key);}
    @GetMapping("/knowledge/spaces/{id}/members") public List<Member> members(@PathVariable UUID id){return knowledge.members(CurrentUser.get(),id);}
    @GetMapping("/knowledge/spaces/{id}/member-candidates") public List<Candidate> candidates(@PathVariable UUID id){return knowledge.candidates(CurrentUser.get(),id);}
    @PutMapping("/knowledge/spaces/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void membership(@PathVariable UUID id,@PathVariable UUID userId,@Valid @RequestBody Membership body){knowledge.membership(CurrentUser.get(),id,userId,body.canRead(),body.canEdit(),body.reason());}
    @GetMapping("/knowledge/spaces/{id}/documents") public PageResult<Summary> documents(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID categoryId){return knowledge.documents(CurrentUser.get(),id,page,size,q,categoryId);}
    @GetMapping("/knowledge/review-tasks") public PageResult<ReviewTask> reviewTasks(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return knowledge.reviewTasks(CurrentUser.get(),page,size);}
    @PostMapping(value="/knowledge/spaces/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public Upload upload(@PathVariable UUID id,@RequestParam String title,@RequestPart MultipartFile file,@RequestHeader(value="Idempotency-Key",required=false) String key){return knowledge.upload(CurrentUser.get(),id,null,title,file,key);}
    @PostMapping(value="/knowledge/documents/{id}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public Upload version(@PathVariable UUID id,@RequestPart MultipartFile file,@RequestHeader(value="Idempotency-Key",required=false) String key){return knowledge.upload(CurrentUser.get(),null,id,null,file,key);}
    @GetMapping("/knowledge/documents/{id}") public Detail detail(@PathVariable UUID id){return knowledge.detail(CurrentUser.get(),id);}
    @GetMapping("/knowledge/documents/{id}/versions/{versionId}/chunks") public List<Chunk> chunks(@PathVariable UUID id,@PathVariable UUID versionId){return knowledge.chunks(CurrentUser.get(),id,versionId);}
    @PostMapping("/knowledge/documents/{id}/versions/{versionId}/publish") public Detail publish(@PathVariable UUID id,@PathVariable UUID versionId){return knowledge.publish(CurrentUser.get(),id,versionId);}
    @PostMapping("/knowledge/documents/{id}/disable") public Detail disable(@PathVariable UUID id){return knowledge.disable(CurrentUser.get(),id);}
    @PostMapping("/knowledge/documents/{id}/versions/{versionId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public Upload retry(@PathVariable UUID id,@PathVariable UUID versionId){return knowledge.retry(CurrentUser.get(),id,versionId);}
    @DeleteMapping("/knowledge/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(@PathVariable UUID id){knowledge.archive(CurrentUser.get(),id);}

    public record CreateSpace(@NotBlank @Size(max=160) String name){}
    public record Membership(@NotNull Boolean canRead,@NotNull Boolean canEdit,@NotBlank @Size(max=500) String reason){}
}

