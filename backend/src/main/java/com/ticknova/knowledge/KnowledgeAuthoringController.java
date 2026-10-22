package com.ticknova.knowledge;

import com.ticknova.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static com.ticknova.knowledge.KnowledgeDtos.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeAuthoringController {
    private final KnowledgeAuthoringService authoring;private final KnowledgeService knowledge;
    public KnowledgeAuthoringController(KnowledgeAuthoringService authoring,KnowledgeService knowledge){this.authoring=authoring;this.knowledge=knowledge;}
    @GetMapping("/spaces/{id}/categories") public List<Category> categories(@PathVariable UUID id){return authoring.categories(CurrentUser.get(),id);}
    @PostMapping("/spaces/{id}/categories") @ResponseStatus(HttpStatus.CREATED) public Category category(@PathVariable UUID id,@Valid @RequestBody NewCategory body){return authoring.createCategory(CurrentUser.get(),id,body.name(),body.parentId());}
    @PatchMapping("/categories/{id}") public Category patchCategory(@PathVariable UUID id,@RequestBody JsonNode body){return authoring.patchCategory(CurrentUser.get(),id,body);}
    @DeleteMapping("/categories/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteCategory(@PathVariable UUID id,@Valid @RequestBody ExpectedVersion body){authoring.deleteCategory(CurrentUser.get(),id,body.version());}
    @PostMapping("/spaces/{id}/articles") @ResponseStatus(HttpStatus.CREATED) public Draft article(@PathVariable UUID id,@Valid @RequestBody Article body,@RequestHeader(value="Idempotency-Key",required=false) String key){return authoring.create(CurrentUser.get(),id,body.title(),body.categoryId(),body.content(),key);}
    @GetMapping("/documents/{id}/draft") public Draft draft(@PathVariable UUID id){return authoring.draft(CurrentUser.get(),id);}
    @GetMapping("/documents/{id}/versions/{versionId}/draft-source") public DraftSource draftSource(@PathVariable UUID id,@PathVariable UUID versionId){return authoring.draftSource(CurrentUser.get(),id,versionId);}
    @GetMapping("/documents/{id}/versions/{versionId}/content") public JsonNode content(@PathVariable UUID id,@PathVariable UUID versionId){return authoring.versionContent(CurrentUser.get(),id,versionId);}
    @PutMapping("/documents/{id}/draft") public Draft save(@PathVariable UUID id,@Valid @RequestBody SaveDraft body){return authoring.save(CurrentUser.get(),id,body.title(),body.categoryId(),body.content(),body.version());}
    @PostMapping("/documents/{id}/draft/versions") @ResponseStatus(HttpStatus.ACCEPTED) public Upload commit(@PathVariable UUID id,@Valid @RequestBody ExpectedVersion body,@RequestHeader(value="Idempotency-Key",required=false) String key){return authoring.commit(CurrentUser.get(),id,body.version(),key);}
    @PostMapping("/documents/{id}/versions/{versionId}/review-requests") public Detail requestReview(@PathVariable UUID id,@PathVariable UUID versionId){return knowledge.requestReview(CurrentUser.get(),id,versionId);}
    @PostMapping("/documents/{id}/versions/{versionId}/reviews") public Detail review(@PathVariable UUID id,@PathVariable UUID versionId,@Valid @RequestBody Review body){return knowledge.review(CurrentUser.get(),id,versionId,body.decision(),body.reason());}
    public record NewCategory(@NotBlank @Size(max=160) String name,UUID parentId){}
    public record Article(@NotBlank @Size(max=240) String title,UUID categoryId,@NotNull JsonNode content){}
    public record SaveDraft(@NotBlank @Size(max=240) String title,UUID categoryId,@NotNull JsonNode content,@NotNull @Min(0) Integer version){}
    public record ExpectedVersion(@NotNull @Min(0) Integer version){}
    public record Review(@NotBlank @Pattern(regexp="APPROVED|REJECTED") String decision,@Size(max=500) String reason){}
}
