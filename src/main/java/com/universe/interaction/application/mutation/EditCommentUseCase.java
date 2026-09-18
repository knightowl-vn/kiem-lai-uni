package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentRevision;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to edit an active comment's body by its author, archiving prior bodies into immutable comment revisions.
 */
@Service
public class EditCommentUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentRevisionRepositoryPort commentRevisionRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public EditCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentRevisionRepositoryPort commentRevisionRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.commentRevisionRepositoryPort = Objects.requireNonNull(commentRevisionRepositoryPort, "CommentRevisionRepositoryPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
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

        // 4. Normalize requested body and check for idempotent no-op
        String normalizedNewBody = command.newBody().trim();
        if (normalizedNewBody.equals(comment.getBody())) {
            return comment;
        }

        // 5. Content-changing edit: archive previous body into CommentRevision
        String previousBody = comment.getBody();
        Instant now = clockPort.now();
        int nextRevisionNumber = commentRevisionRepositoryPort.getNextRevisionNumber(comment.getId());
        UUID revisionId = idGeneratorPort.generate();

        CommentRevision revision = new CommentRevision(
                revisionId,
                comment.getId(),
                nextRevisionNumber,
                previousBody,
                now
        );
        commentRevisionRepositoryPort.save(revision);

        // 6. Mutate comment to new body with the same timestamp
        comment.edit(normalizedNewBody, now);

        // 7. Save and return updated comment
        return commentRepositoryPort.save(comment);
    }
}
