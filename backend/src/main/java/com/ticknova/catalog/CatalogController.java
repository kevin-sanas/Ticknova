package com.ticknova.catalog;

import com.ticknova.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping("/queues") public List<CatalogService.QueueView> queues() { return catalog.queues(CurrentUser.get()); }
    @PostMapping("/queues") @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.QueueView createQueue(@Valid @RequestBody Configuration body) { return catalog.createQueue(CurrentUser.get(),body.code(),body.name(),body.active()==null || body.active()); }
    @PatchMapping("/queues/{id}") public CatalogService.QueueView patchQueue(@PathVariable UUID id,@Valid @RequestBody PatchConfiguration body) { return catalog.patchQueue(CurrentUser.get(),id,body.code(),body.name(),body.active()); }
    @GetMapping("/queues/{id}/members") public List<CatalogService.MemberView> members(@PathVariable UUID id) { return catalog.members(CurrentUser.get(),id); }
    @PutMapping("/queues/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMembership(@PathVariable UUID id,@PathVariable UUID userId,@Valid @RequestBody Membership body) { catalog.setMembership(CurrentUser.get(),id,userId,body.canProcess()); }
    @DeleteMapping("/queues/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMembership(@PathVariable UUID id,@PathVariable UUID userId) { catalog.removeMembership(CurrentUser.get(),id,userId); }
    @GetMapping("/services") public List<CatalogService.ServiceView> services(@RequestParam(defaultValue="false") boolean includeInactive) { return catalog.services(CurrentUser.get(),includeInactive); }
    @PostMapping("/services") @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.ServiceView createService(@Valid @RequestBody Configuration body) { return catalog.createService(CurrentUser.get(),body.code(),body.name(),body.active()==null || body.active()); }
    @PatchMapping("/services/{id}") public CatalogService.ServiceView patchService(@PathVariable UUID id,@Valid @RequestBody PatchConfiguration body) { return catalog.patchService(CurrentUser.get(),id,body.code(),body.name(),body.active()); }
    public record Configuration(@NotBlank @Size(max=80) String code,@NotBlank @Size(max=160) String name,Boolean active) {}
    public record PatchConfiguration(@Size(min=1,max=80) String code,@Size(min=1,max=160) String name,Boolean active) {}
    public record Membership(@NotNull Boolean canProcess) {}
}
