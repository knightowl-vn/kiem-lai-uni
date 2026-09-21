package com.universe.interaction.application.ports;

import com.universe.interaction.domain.report.InteractionReport;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for persisting and querying {@link InteractionReport} domain aggregates.
 */
public interface InteractionReportRepositoryPort {

    /**
     * Saves or updates an {@link InteractionReport} aggregate.
     *
     * @param report the domain aggregate to save
     * @return the saved domain aggregate
     */
    InteractionReport save(InteractionReport report);

    /**
     * Finds an {@link InteractionReport} aggregate by its unique ID.
     *
     * @param id the unique report UUID
     * @return an Optional containing the domain report if found
     */
    Optional<InteractionReport> findById(UUID id);

    /**
     * Checks whether an active PENDING report already exists for the given comment and reporter.
     *
     * @param commentId the ID of the target comment
     * @param reporterUserId the ID of the reporting user
     * @return true if a PENDING report already exists for this pair
     */
    boolean existsPendingByCommentIdAndReporterUserId(UUID commentId, UUID reporterUserId);

    /**
     * Finds an {@link InteractionReport} aggregate by its unique ID with an exclusive pessimistic write lock
     * (SELECT ... FOR UPDATE).
     *
     * <p>Callers must invoke this method within an active transaction to protect against concurrent resolution races.
     *
     * @param reportId the unique report UUID
     * @return an Optional containing the locked domain report if found, empty otherwise
     */
    Optional<InteractionReport> findByIdForUpdate(UUID reportId);
}
