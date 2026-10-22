package com.ticknova.ai;

import java.util.*;

public final class AiDtos {
    private AiDtos() {}
    public record Config(String mode,String profile,boolean externalProvider) {}
    public record Created(UUID jobId) {}
    public record AnswerRequest(String question,List<UUID> spaceIds) {}
    public record DraftRequest(String kind,List<UUID> spaceIds) {}
    public record FeedbackRequest(String feedback,String reason) {}
    public record Job(UUID id,String status,String errorCode,int attemptCount,AiResult result) {}
    public record Citation(UUID documentId,UUID versionId,int version,String title,Integer page,UUID chunkId,String snippet) {}
    public record AiResult(UUID suggestionId,String kind,String mode,String profile,String outcome,String answer,String summary,
        UUID serviceId,String priority,List<String> tags,List<Citation> citations,boolean requiresHumanReview,String feedback) {}
}
