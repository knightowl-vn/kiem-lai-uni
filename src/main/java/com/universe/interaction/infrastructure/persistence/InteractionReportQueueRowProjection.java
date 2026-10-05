package com.universe.interaction.infrastructure.persistence;

import java.time.Instant;

/**
 * Spring Data JPA projection for joined rows from {@code interaction_reports} and {@code interaction_comments}.
 *
 * <p>Infrastructure-only scalar projection. Never escapes the persistence adapter boundary.
 */
public interface InteractionReportQueueRowProjection {

    String getReportId();

    String getReportTargetType();

    String getReportTargetId();

    String getReporterUserId();

    String getReason();

    String getDescription();

    String getReportedContentSnapshot();

    String getReportStatus();

    Instant getCreatedAt();

    String getCommentAuthorUserId();

    String getContentTargetType();

    String getContentTargetId();

    String getCommentStatus();

    String getModerationAction();

    String getResolvedByUserId();

    Instant getResolvedAt();

    Instant getTargetDeletedAt();

    // Backward-compatible projection aliases if referenced
    default String getCommentId() {
        return getReportTargetId();
    }

    default String getReportedBodySnapshot() {
        return getReportedContentSnapshot();
    }

    default String getTargetType() {
        return getContentTargetType();
    }

    default String getTargetId() {
        return getContentTargetId();
    }
}
