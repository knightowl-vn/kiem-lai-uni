package com.universe.interaction.entry.dto;

import com.universe.interaction.application.query.CommentThreadView;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable container for a single discussion thread consisting of an active root comment and visible flat replies.
 */
public record CommentThreadResponseDTO(
        CommentReadDTO root,
        List<CommentReadDTO> replies
) {
    public CommentThreadResponseDTO {
        Objects.requireNonNull(root, "Root comment cannot be null.");
        replies = replies == null ? List.of() : List.copyOf(replies);
    }

    public static CommentThreadResponseDTO from(CommentThreadView view) {
        return from(view, (UUID) null);
    }

    public static CommentThreadResponseDTO from(CommentThreadView view, UUID viewerUserId) {
        return from(view, viewerUserId, null);
    }

    public static CommentThreadResponseDTO from(
            CommentThreadView view,
            UUID viewerUserId,
            java.util.Map<UUID, ReactionSummaryResponseDTO> reactionSummaries
    ) {
        Objects.requireNonNull(view, "CommentThreadView cannot be null.");
        ReactionSummaryResponseDTO rootSummary = (reactionSummaries != null && view.root() != null)
                ? reactionSummaries.get(view.root().id())
                : null;
        CommentReadDTO rootDTO = CommentReadDTO.from(view.root(), viewerUserId, rootSummary);
        List<CommentReadDTO> replyDTOs = view.replies().stream()
                .map(reply -> {
                    ReactionSummaryResponseDTO replySummary = (reactionSummaries != null && reply != null)
                            ? reactionSummaries.get(reply.id())
                            : null;
                    return CommentReadDTO.from(reply, viewerUserId, replySummary);
                })
                .toList();
        return new CommentThreadResponseDTO(rootDTO, replyDTOs);
    }
}
