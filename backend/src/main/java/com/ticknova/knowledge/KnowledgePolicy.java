package com.ticknova.knowledge;

import com.ticknova.shared.*;
import org.springframework.stereotype.Component;

@Component
public class KnowledgePolicy {
    public boolean editor(Actor actor) { return actor.hasRole("KB_EDITOR") || actor.hasRole("ADMIN"); }
    public void requireEditor(Actor actor) { if(!editor(actor)) throw new ApiException(403,"KNOWLEDGE_EDIT_FORBIDDEN","需要知识编辑者或管理员角色"); }
}
