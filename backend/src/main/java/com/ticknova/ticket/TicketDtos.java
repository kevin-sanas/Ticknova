package com.ticknova.ticket;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TicketDtos {
    public static final int COMMENT_MAX_LENGTH = 10_000;
    private TicketDtos() {}
    public record Create(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=20000) String description,
                         UUID serviceId,@Pattern(regexp="LOW|NORMAL|HIGH|URGENT") String priority) {}
    public record CommentRequest(@NotBlank @Pattern(regexp="PUBLIC_REPLY|INTERNAL_NOTE") String visibility,
                                 @NotBlank @Size(max=COMMENT_MAX_LENGTH) String body) {}
    public record TransitionRequest(@NotBlank String toStatus,@Size(max=10000) String reason,@PositiveOrZero Long version) {}
    public record Edit(@Pattern(regexp="LOW|NORMAL|HIGH|URGENT") String priority,
                       @Size(max=10) List<@NotBlank @Size(max=32) String> tags,@PositiveOrZero Long version) {}
    public record Assignment(@NotNull UUID queueId,UUID assigneeId,@PositiveOrZero Long version) {}
    public record Summary(UUID id,long ticketNo,String title,String priority,String status,UUID requesterId,String requesterName,
                          UUID queueId,String queueName,UUID assigneeId,String assigneeName,UUID serviceId,String serviceName,
                          long version,Instant createdAt,Instant updatedAt,List<String> tags) {}
    public record Comment(UUID id,UUID ticketId,UUID authorId,String authorName,String visibility,String body,Instant createdAt) {}
    public record Transition(UUID id,String fromStatus,String toStatus,String reason,String actorName,Instant createdAt) {}
    public record Capabilities(boolean canCommentPublic,boolean canCommentInternal,boolean canAssign,boolean canEdit,boolean canReadInternal) {}
    public record Detail(Summary ticket,String description,String resolutionNote,List<Comment> publicReplies,
                         List<Comment> internalNotes,List<Transition> transitions,Capabilities capabilities,List<String> allowedTransitions) {}
}
