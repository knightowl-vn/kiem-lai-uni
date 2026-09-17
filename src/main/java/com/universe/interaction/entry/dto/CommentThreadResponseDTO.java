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
        Objects.requireNonNull(view, "CommentThreadView cannot be null.");
        return new CommentThreadResponseDTO(
                CommentReadDTO.from(view.root(), viewerUserId),
                view.replies().stream().map(reply -> CommentReadDTO.from(reply, viewerUserId)).toList()
        );
    }
}
