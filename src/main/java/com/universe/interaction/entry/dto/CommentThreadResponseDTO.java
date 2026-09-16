package com.universe.interaction.entry.dto;

import com.universe.interaction.application.query.CommentThreadView;

import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(view, "CommentThreadView cannot be null.");
        return new CommentThreadResponseDTO(
                CommentReadDTO.from(view.root()),
                view.replies().stream().map(CommentReadDTO::from).toList()
        );
    }
}
