package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for {@link ScreenEntry}.
 */
public interface ScreenEntryRepositoryPort {

    ScreenEntry save(ScreenEntry entry);

    Optional<ScreenEntry> findById(UUID id);

    /**
     * Retrieves entry with pessimistic row-level lock (FOR UPDATE) for safe concurrent mutations.
     */
    Optional<ScreenEntry> findByIdForUpdate(UUID id);

    Optional<ScreenEntry> findByTitleIdAndSlug(UUID titleId, String slug);

    Optional<ScreenEntry> findPublishedById(UUID id);

    Optional<ScreenEntry> findPublishedByTitleIdAndSlug(UUID titleId, String slug);

    /**
     * Generic retrieval of all entries for a title.
     * NOTE: GENERIC ENTRY ORDER != PUBLIC NAVIGATION ORDER.
     * Total order is deterministic by id ASC only.
     */
    List<ScreenEntry> findByTitleId(UUID titleId);

    /**
     * Generic retrieval of published entries for a title (excludes DRAFT and ARCHIVED).
     * NOTE: GENERIC ENTRY ORDER != PUBLIC NAVIGATION ORDER.
     * Total order is deterministic by id ASC only.
     */
    List<ScreenEntry> findPublishedByTitleId(UUID titleId);

    /**
     * SERIES episode navigation primitive.
     * Retrieves only PUBLISHED entries of type EPISODE with valid positive number,
     * ordered deterministically for previous/next playback navigation:
     * ORDER BY number ASC, id ASC.
     */
    List<ScreenEntry> findPublishedEpisodesByTitleId(UUID titleId);

    boolean existsById(UUID id);

    boolean existsByTitleIdAndSlug(UUID titleId, String slug);
}
