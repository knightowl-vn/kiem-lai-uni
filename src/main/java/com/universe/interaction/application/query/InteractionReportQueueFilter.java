package com.universe.interaction.application.query;

import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;

import java.util.Objects;

/**
 * Filter and pagination criteria for the interaction report queue.
 *
 * <p>Framework-free query descriptor. Does not encode web/HTTP defaults.
 *
 * @param scope lifecycle queue scope (required, never null)
 * @param reason nullable report reason filter (null = all reasons)
 * @param targetType nullable comment target type filter (null = all target types)
 * @param sort deterministic sort order (required)
 * @param page zero-based page index (>= 0)
 * @param size page size (> 0)
 */
public record InteractionReportQueueFilter(
        ReportQueueLifecycleScope scope,
        ReportReason reason,
        CommentTargetType targetType,
        InteractionReportQueueSort sort,
        int page,
        int size
) {
    public InteractionReportQueueFilter {
        Objects.requireNonNull(scope, "ReportQueueLifecycleScope cannot be null.");
        Objects.requireNonNull(sort, "InteractionReportQueueSort cannot be null.");
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }
    }
}
