package com.ticknova.workspace;

import com.ticknova.shared.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.ticknova.workspace.WorkspaceDtos.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService service;
    public WorkspaceController(WorkspaceService service){this.service=service;}
    @GetMapping("/workspace/portal")
    public Portal portal(@RequestParam(required=false) String q){return service.portal(CurrentUser.get(),q);}
    @GetMapping("/workspace/analytics")
    public Analytics analytics(@RequestParam(required=false) String from,@RequestParam(required=false) String to){return service.analytics(CurrentUser.get(),from,to);}
    @PostMapping("/knowledge/documents/{id}/views") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@PathVariable UUID id){service.view(CurrentUser.get(),id);}
}
