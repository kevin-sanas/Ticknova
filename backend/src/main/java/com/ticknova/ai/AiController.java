package com.ticknova.ai;

import com.ticknova.shared.*;
import com.ticknova.knowledge.KnowledgeService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.ticknova.ai.AiDtos.*;

@RestController
@RequestMapping("/api/v1")
public class AiController {
    private final AiService ai;
    public AiController(AiService ai){this.ai=ai;}
    @GetMapping("/ai/config") public Config config(){return ai.config();}
    @PostMapping("/ai/answers") @ResponseStatus(HttpStatus.ACCEPTED)
    public Created answer(@RequestBody AnswerRequest body,@RequestHeader(value="Idempotency-Key",required=false) String key){return ai.answer(CurrentUser.get(),body,key);}
    @PostMapping("/tickets/{id}/ai-drafts") @ResponseStatus(HttpStatus.ACCEPTED)
    public Created draft(@PathVariable UUID id,@RequestBody DraftRequest body,@RequestHeader(value="Idempotency-Key",required=false) String key){return ai.draft(CurrentUser.get(),id,body,key);}
    @PostMapping("/ai/jobs/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id){ai.cancel(CurrentUser.get(),id);}
    @PostMapping("/ai/suggestions/{id}/feedback") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@PathVariable UUID id,@RequestBody FeedbackRequest body){ai.feedback(CurrentUser.get(),id,body);}
}

