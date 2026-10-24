package com.ticknova.knowledge;

import java.time.Instant;
import java.util.*;

public final class KnowledgeDtos {
    private KnowledgeDtos() {}
    public record Space(UUID id,String name,boolean active,boolean canRead,boolean canEdit,boolean canManageMembers) {}
    public record Member(UUID userId,String username,String displayName,boolean canRead,boolean canEdit) {}
    public record Candidate(UUID id,String username,String displayName) {}
    public record Upload(UUID documentId,UUID versionId,UUID jobId) {}
    public record Summary(UUID id,UUID spaceId,String title,String publicationState,UUID latestVersionId,UUID publishedVersionId,String latestIngestState,int versionCount,Instant createdAt,boolean canEdit,UUID categoryId) {}
    public record Detail(UUID id,UUID spaceId,String title,String publicationState,UUID latestVersionId,UUID publishedVersionId,String latestIngestState,int versionCount,Instant createdAt,boolean canEdit,List<Version> versions,UUID categoryId) {}
    public record Version(UUID id,int versionNo,String fileName,String mimeType,String sha256,String ingestState,String publicationState,String errorCode,Instant createdAt,UUID jobId,String jobStatus,int attemptCount,int chunkCount,String reviewState,UUID authorId,UUID reviewerId,String reviewReason,Instant reviewedAt,boolean canRequestReview,boolean canReview,boolean canPublish,boolean hasEligibleReviewer,String embeddingState,String embeddingErrorCode,boolean canRetryIndex) {}
    public record Category(UUID id,UUID spaceId,UUID parentId,String name,int version) {}
    public record Draft(UUID id,UUID spaceId,String title,UUID categoryId,tools.jackson.databind.JsonNode content,int version,Instant updatedAt,UUID updatedBy) {}
    public record DraftSource(UUID versionId,int versionNo,boolean importable,String reason,tools.jackson.databind.JsonNode content) {}
    public record ReviewTask(UUID documentId,UUID spaceId,String title,UUID versionId,int versionNo,String fileName,UUID authorId,Instant versionCreatedAt) {}
    public record Chunk(UUID id,int chunkIndex,Integer pageNo,String headingPath,String content) {}
    public record Job(UUID id,String status,String errorCode,int attemptCount,UUID versionId,UUID documentId) {}
}

