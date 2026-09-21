package com.universe.interaction.entry.admin.dto;

import com.universe.interaction.domain.CommentTargetType;

import java.util.Objects;
import java.util.UUID;

/**
 * Presentation-layer navigation projection for locating reported comments in public discussion.
 *
 * <p>Contains strictly structural data needed to route administrators to the exact comment/reply
 * inside live public Novel or Wiki discussion feeds.
 *
 * @param reportId unique report ID (never null)
 * @param commentId exact comment ID (nullable if unavailable)
 * @param threadId root thread comment ID (nullable if unavailable)
 * @param targetType target context type (nullable if unavailable)
 * @param slug target entity URL slug (nullable if unavailable)
 * @param articleType raw structural wiki article type from target metadata (nullable if unavailable or novel, e.g. "CHARACTER")
 * @param available whether context navigation is fully resolvable and permissible
 */
public record AdminCommentReportContextNavigationDTO(
        UUID reportId,
        UUID commentId,
        UUID threadId,
        CommentTargetType targetType,
        String slug,
        String articleType,
        boolean available
) {
    public AdminCommentReportContextNavigationDTO {
        Objects.requireNonNull(reportId, "reportId cannot be null");
    }

    public static AdminCommentReportContextNavigationDTO available(
            UUID reportId,
            UUID commentId,
            UUID threadId,
            CommentTargetType targetType,
            String slug,
            String articleType
    ) {
        Objects.requireNonNull(reportId, "reportId cannot be null");
        Objects.requireNonNull(commentId, "commentId cannot be null");
        Objects.requireNonNull(threadId, "threadId cannot be null");
        Objects.requireNonNull(targetType, "targetType cannot be null");
        Objects.requireNonNull(slug, "slug cannot be null");
        return new AdminCommentReportContextNavigationDTO(
                reportId,
                commentId,
                threadId,
                targetType,
                slug,
                articleType,
                true
        );
    }

    public static AdminCommentReportContextNavigationDTO unavailable(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId cannot be null");
        return new AdminCommentReportContextNavigationDTO(
                reportId,
                null,
                null,
                null,
                null,
                null,
                false
        );
    }
}
