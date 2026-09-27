package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Application query use case to resolve discussion metrics for a target without entity hydration or N+1 queries.
 */
@Service
public class GetCommentTargetMetricsUseCase {

    private final CommentRepositoryPort commentRepositoryPort;

    public GetCommentTargetMetricsUseCase(CommentRepositoryPort commentRepositoryPort) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    /**
     * Resolves discussion metrics (threadCount, commentCount) for the specified target.
     *
     * @param target target entity (cannot be null)
     * @return immutable discussion metrics
     */
    @Transactional(readOnly = true)
    public CommentTargetMetrics execute(CommentTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }
        return commentRepositoryPort.getMetricsForTarget(target);
    }
}
