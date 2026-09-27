package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Interaction application query use case to count visible active replies for a collection of thread root IDs.
 *
 * <p>Supports indicator read models and comment count computations without hydrating reply entities or risking N+1 queries.
 */
@Service
public class CountVisibleActiveRepliesByRootIdsUseCase {

    private final CommentRepositoryPort commentRepositoryPort;

    public CountVisibleActiveRepliesByRootIdsUseCase(CommentRepositoryPort commentRepositoryPort) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    /**
     * Counts visible active replies grouped by root comment ID.
     *
     * @param rootCommentIds collection of thread root comment IDs
     * @return map of rootCommentId to count of active replies
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> execute(Collection<UUID> rootCommentIds) {
        if (rootCommentIds == null || rootCommentIds.isEmpty()) {
            return Map.of();
        }
        return commentRepositoryPort.countActiveRepliesByThreadRootIds(rootCommentIds);
    }
}
