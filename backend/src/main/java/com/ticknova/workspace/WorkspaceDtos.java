package com.ticknova.workspace;

import java.time.*;
import java.util.*;

public final class WorkspaceDtos {
    private WorkspaceDtos() {}
    public record KnowledgeHit(UUID id,UUID spaceId,String title,String snippet,UUID categoryId,long viewCount) {}
    public record TicketHit(UUID id,long ticketNo,String title,String status,String priority,Instant updatedAt) {}
    public record PortalCategory(UUID id,UUID spaceId,UUID parentId,String name,String spaceName) {}
    public record Portal(String query,List<KnowledgeHit> knowledge,List<TicketHit> tickets,List<KnowledgeHit> popularDocuments,List<PortalCategory> categories) {}
    public record Count(String key,long count) {}
    public record Daily(LocalDate date,long created,long resolved) {}
    public record KnowledgeCounts(long publishedDocuments,long pendingReviews) {}
    public record AiCounts(long completed,long refused,long accepted,long rejected) {}
    public record Analytics(LocalDate from,LocalDate to,long ticketTotal,List<Count> statusCounts,List<Count> priorityCounts,List<Daily> daily,KnowledgeCounts knowledge,AiCounts ai) {}
}
