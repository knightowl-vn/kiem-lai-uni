package com.universe.interaction.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link InteractionReportJpaEntity}.
 */
@Repository
public interface SpringDataInteractionReportRepository extends JpaRepository<InteractionReportJpaEntity, String> {

    /**
     * Checks if a report exists for a specific comment, reporter, and status.
     *
     * @param commentId the target comment ID string
     * @param reporterUserId the reporter user ID string
     * @param status the status string (e.g. "PENDING")
     * @return true if a matching report exists
     */
    boolean existsByCommentIdAndReporterUserIdAndStatus(
            String commentId,
            String reporterUserId,
            String status
    );
}
