package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.shared.time.ClockPort;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Use case to edit an active comment's body by its author.
 */
public class EditCommentUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final ClockPort clockPort;

    public EditCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public Comment execute(EditCommentCommand command) {
        Objects.requireNonNull(command, "EditCommentCommand cannot be null.");

        // 1. Load current comment with row lock
        Comment comment = commentRepositoryPort.findByIdForUpdate(command.commentId())
                .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + command.commentId()));

        // 2. Author check
        if (!comment.getAuthorUserId().equals(command.actorUserId())) {
            throw new CommentMutationForbiddenException(
                    "User " + command.actorUserId() + " is not the author of comment " + command.commentId()
            );
        }

        // 3. Deleted comment check
        if (comment.isDeleted()) {
            throw new CommentMutationForbiddenException("Cannot edit a deleted comment: " + command.commentId());
        }

        // 4. Capture edit timestamp once and edit
        Instant editedAt = clockPort.now();
        comment.edit(command.newBody(), editedAt);

        // 5. Save and return
        return commentRepositoryPort.save(comment);
    }
}
