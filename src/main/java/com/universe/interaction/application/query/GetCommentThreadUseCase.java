package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to retrieve a single discussion thread consisting of an active root comment and visible flat replies.
 *
 * <p>Preserves clean architecture and security contracts:
 * <ul>
 *   <li>Reads do not take mutation locks;</li>
 *   <li>Missing roots throw {@link CommentNotFoundException};</li>
 *   <li>Non-root identifiers throw {@link CommentThreadIntegrityException};</li>
 *   <li>Deleted root threads are hidden from normal read presentation;</li>
 *   <li>Delegates flat reply tombstone retention to {@link CommentThreadVisibilityResolver}.</li>
 * </ul>
 */
@Service
public class GetCommentThreadUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentThreadVisibilityResolver visibilityResolver;

    public GetCommentThreadUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentThreadVisibilityResolver visibilityResolver
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.visibilityResolver = Objects.requireNonNull(visibilityResolver, "CommentThreadVisibilityResolver cannot be null.");
    }

    @Transactional(readOnly = true)
    public CommentThreadView execute(UUID rootCommentId) {
        if (rootCommentId == null) {
            throw new IllegalArgumentException("Root comment ID cannot be null.");
        }

        Comment root = commentRepositoryPort.findById(rootCommentId)
                .orElseThrow(() -> new CommentNotFoundException(rootCommentId));

        if (!root.isRoot()) {
            throw new CommentThreadIntegrityException("Comment " + rootCommentId + " is not a root comment.");
        }

        if (root.isDeleted()) {
            throw new CommentNotFoundException(rootCommentId);
        }

        List<Comment> rawReplies = commentRepositoryPort.findThreadReplies(rootCommentId);
        List<CommentReadItem> visibleReplies = visibilityResolver.resolve(root, rawReplies);
        CommentReadItem rootItem = CommentReadItem.fromRoot(root);

        return new CommentThreadView(rootItem, visibleReplies);
    }
}
