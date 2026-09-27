package com.universe.interaction.infrastructure.persistence;

import java.time.Instant;

/**
 * Spring Data JPA projection for joined rows from {@code interaction_reports} and {@code interaction_comments}.
 *
 * <p>Infrastructure-only scalar projection. Never escapes the persistence adapter boundary.
 */
public interface InteractionReportQueueRowProjection {

    String getReportId();

    String getCommentId();

    String getReporterUserId();

    String getReason();

    String getDescription();

    String getReportedBodySnapshot();

    String getReportStatus();

    Instant getCreatedAt();

    String getCommentAuthorUserId();

    String getTargetType();

    String getTargetId();

    String getCommentStatus();

    String getModerationAction();

    String getResolvedByUserId();

    Instant getResolvedAt();
}
