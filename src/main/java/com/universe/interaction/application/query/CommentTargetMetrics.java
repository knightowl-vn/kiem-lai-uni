package com.universe.interaction.application.query;

/**
 * Immutable metrics representing visible discussion activity for a target entity.
 *
 * <p>Semantics:
 * <ul>
 *   <li>threadCount: number of visible ACTIVE root threads for the target;</li>
 *   <li>commentCount: number of visible ACTIVE comments belonging to visible threads
 *       (ACTIVE roots + ACTIVE replies under visible ACTIVE roots; excludes tombstones
 *       and replies under deleted roots).</li>
 * </ul>
 */
public record CommentTargetMetrics(
        int threadCount,
        int commentCount
) {
    public static final CommentTargetMetrics EMPTY = new CommentTargetMetrics(0, 0);

    public CommentTargetMetrics {
        if (threadCount < 0) {
            throw new IllegalArgumentException("threadCount cannot be negative: " + threadCount);
        }
        if (commentCount < 0) {
            throw new IllegalArgumentException("commentCount cannot be negative: " + commentCount);
        }
        if (commentCount < threadCount) {
            throw new IllegalArgumentException(
                    "commentCount (" + commentCount + ") cannot be less than threadCount (" + threadCount + ")"
            );
        }
    }

    public CommentTargetMetrics(long threadCount, long commentCount) {
        this((int) threadCount, (int) commentCount);
    }
}
