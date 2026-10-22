package com.ticknova.ai;

import com.ticknova.knowledge.KnowledgeService;
import com.ticknova.shared.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    private final AiService ai;
    private final KnowledgeService knowledge;
    public JobController(AiService ai,KnowledgeService knowledge){this.ai=ai;this.knowledge=knowledge;}
    @GetMapping("/{id}") public Object job(@PathVariable UUID id){return ai.isAiJob(id)?ai.job(CurrentUser.get(),id):knowledge.job(CurrentUser.get(),id);}
}
