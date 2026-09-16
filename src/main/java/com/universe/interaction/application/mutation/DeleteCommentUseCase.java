package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Use case to soft-delete (tombstone) a comment by its author.
 */
@Service
public class DeleteCommentUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final ClockPort clockPort;

    public DeleteCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public Comment execute(DeleteCommentCommand command) {
        Objects.requireNonNull(command, "DeleteCommentCommand cannot be null.");

        // 1. Load current comment with row lock
        Comment comment = commentRepositoryPort.findByIdForUpdate(command.commentId())
                .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + command.commentId()));

        // 2. Author check
        if (!comment.getAuthorUserId().equals(command.actorUserId())) {
            throw new CommentMutationForbiddenException(
                    "User " + command.actorUserId() + " is not the author of comment " + command.commentId()
            );
        }

        // 3. Idempotency check: if already deleted, preserve first deletion timestamp and return
        if (comment.isDeleted()) {
            return comment;
        }

        // 4. Capture delete timestamp once and tombstone
        Instant deletedAt = clockPort.now();
        comment.delete(deletedAt);

        // 5. Save and return tombstone
        return commentRepositoryPort.save(comment);
    }
}
