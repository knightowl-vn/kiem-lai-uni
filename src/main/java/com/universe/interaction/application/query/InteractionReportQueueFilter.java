package com.universe.interaction.application.query;

import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.util.Objects;

/**
 * Filter and pagination criteria for the interaction report queue.
 *
 * <p>Framework-free query descriptor. Does not encode web/HTTP defaults.
 *
 * @param status nullable report status filter (null = all statuses)
 * @param reason nullable report reason filter (null = all reasons)
 * @param targetType nullable comment target type filter (null = all target types)
 * @param sort deterministic sort order (required)
 * @param page zero-based page index (>= 0)
 * @param size page size (> 0)
 */
public record InteractionReportQueueFilter(
        ReportStatus status,
        ReportReason reason,
        CommentTargetType targetType,
        InteractionReportQueueSort sort,
        int page,
        int size
) {
    public InteractionReportQueueFilter {
        Objects.requireNonNull(sort, "InteractionReportQueueSort cannot be null.");
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }
    }
}
