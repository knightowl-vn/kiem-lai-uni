package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Interaction application use case to query all visible (active) root comment IDs for a target.
 *
 * <p>Supports indicator read models and bulk cross-context composition without hydrating full entities.
 */
@Service
public class FindVisibleRootCommentIdsUseCase {

    private final CommentRepositoryPort commentRepositoryPort;

    public FindVisibleRootCommentIdsUseCase(CommentRepositoryPort commentRepositoryPort) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    /**
     * Finds the IDs of all active root comments for the specified target.
     *
     * @param target the comment target, cannot be null
     * @return immutable set of active root comment UUIDs
     */
    @Transactional(readOnly = true)
    public Set<UUID> execute(CommentTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }
        return Set.copyOf(commentRepositoryPort.findActiveRootCommentIds(target));
    }
}
