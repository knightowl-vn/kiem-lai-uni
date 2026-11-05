package com.universe.screen.contracts.interfaces;

import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.dto.ScreenTitleNavigationDTO;
import com.universe.screen.contracts.dto.ScreenTitleReportTargetDTO;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public Screen contract exposing query and validation operations for Interaction discussion,
 * comment origin tracking, and admin moderation reports.
 *
 * <p>Preserves bounded context boundaries: foreign modules consume this contract without
 * depending directly on Screen internal domain, application, or infrastructure packages.
 */
public interface ScreenDiscussionContract {

    /**
     * Checks whether a ScreenTitle exists and is currently PUBLISHED.
     *
     * @param titleId ScreenTitle UUID
     * @return true if published, false otherwise
     */
    boolean isPublishedTitle(UUID titleId);

    /**
     * Validates whether an entry origin ID belongs to the specified published ScreenTitle
     * and is itself PUBLISHED.
     *
     * @param titleId ScreenTitle UUID
     * @param entryId ScreenEntry UUID
     * @return true if valid published origin, false otherwise
     */
    boolean isValidPublishedOrigin(UUID titleId, UUID entryId);

    /**
     * Resolves all published entry origins for a ScreenTitle in batch, mapped by entryId.
     * Each entry origin contains the entryId, slug, and formatted display badge (e.g. "Tập 1", "Bản chiếu chính").
     *
     * @param titleId ScreenTitle UUID
     * @return map of entryId to ScreenEntryOriginDTO
     */
    Map<UUID, ScreenEntryOriginDTO> findPublishedEntryOriginsByTitleId(UUID titleId);

    /**
     * Resolves title display metadata for Admin comment report queue in batch.
     *
     * @param titleIds set of ScreenTitle UUIDs
     * @return map of titleId to ScreenTitleReportTargetDTO
     */
    Map<UUID, ScreenTitleReportTargetDTO> findReportTargetTitlesByIds(Set<UUID> titleIds);

    /**
     * Resolves single title metadata for Admin comment report detail.
     *
     * @param titleId ScreenTitle UUID
     * @return Optional of ScreenTitleReportTargetDTO
     */
    Optional<ScreenTitleReportTargetDTO> findReportTargetTitleById(UUID titleId);

    /**
     * Formats entry origin badge for Admin comment report detail, ensuring the entry
     * belongs to the specified titleId.
     *
     * @param titleId ScreenTitle UUID
     * @param entryId ScreenEntry UUID
     * @return Optional containing the formatted badge text, or empty if entry not found or mismatched
     */
    Optional<String> findEntryBadgeForReport(UUID titleId, UUID entryId);

    /**
     * Resolves public navigation metadata for Admin context navigation.
     * Returns present only if the title exists, is PUBLISHED, and has a non-blank slug.
     *
     * @param titleId ScreenTitle UUID
     * @return Optional containing navigation DTO, or empty if not navigable
     */
    Optional<ScreenTitleNavigationDTO> findPublishedNavigationByTitleId(UUID titleId);
}
