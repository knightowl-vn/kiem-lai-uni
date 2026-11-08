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
     * Authoritative scalar projection for an entry's current video MediaAsset binding.
     *
     * <p>Used by post-lock current-read consistency checks to bypass Hibernate L1 entity
     * identity caching and observe fresh database column values.
     *
     * @param entryId           non-null ScreenEntry UUID
     * @param videoMediaAssetId attached video MediaAsset UUID, or null if detached
     */
    record EntryVideoBinding(UUID entryId, UUID videoMediaAssetId) {
        public EntryVideoBinding {
            java.util.Objects.requireNonNull(entryId, "entryId cannot be null.");
        }
    }

    /**
     * Generic retrieval of published entries for a title (excludes DRAFT and ARCHIVED).
     * NOTE: GENERIC ENTRY ORDER != PUBLIC NAVIGATION ORDER.
     * Total order is deterministic by id ASC only.
     */
    List<ScreenEntry> findPublishedByTitleId(UUID titleId);

    /**
     * Executes an InnoDB locking current-read returning authoritative scalar
     * {@code (entryId, videoMediaAssetId)} bindings for PUBLISHED child entries of a title,
     * bypassing Hibernate's L1 persistence context cache.
     *
     * @param titleId parent ScreenTitle UUID
     * @return list of bindings ordered by {@code entryId ASC}
     */
    default List<EntryVideoBinding> findPublishedEntryVideoBindingsByTitleIdCurrentRead(UUID titleId) {
        return findPublishedByTitleId(titleId).stream()
                .sorted(java.util.Comparator.comparing(ScreenEntry::getId))
                .map(entry -> new EntryVideoBinding(entry.getId(), entry.getVideoMediaAssetId()))
                .toList();
    }

    /**
     * Executes an InnoDB locking current-read returning authoritative scalar
     * {@code (entryId, videoMediaAssetId)} bindings for all child entries of a title,
     * bypassing Hibernate's L1 persistence context cache.
     *
     * @param titleId parent ScreenTitle UUID
     * @return list of bindings ordered by {@code entryId ASC}
     */
    default List<EntryVideoBinding> findEntryVideoBindingsByTitleIdCurrentRead(UUID titleId) {
        return findByTitleId(titleId).stream()
                .sorted(java.util.Comparator.comparing(ScreenEntry::getId))
                .map(entry -> new EntryVideoBinding(entry.getId(), entry.getVideoMediaAssetId()))
                .toList();
    }

    /**
     * SERIES episode navigation primitive.
     * Retrieves only PUBLISHED entries of type EPISODE with valid positive number,
     * ordered deterministically for previous/next playback navigation:
     * ORDER BY number ASC, id ASC.
     */
    List<ScreenEntry> findPublishedEpisodesByTitleId(UUID titleId);

    /**
     * Batch queries the maximum published episode number (type EPISODE, status PUBLISHED, number > 0)
     * for a collection of title IDs in a single query.
     *
     * @param titleIds collection of title UUIDs to query
     * @return map of title ID to latest published episode number (positive integer)
     */
    java.util.Map<UUID, Integer> findLatestPublishedEpisodeNumbersByTitleIds(java.util.Collection<UUID> titleIds);

    /**
     * Finds the maximum published episode number for a single title ID.
     *
     * @param titleId title UUID
     * @return maximum published episode number, or null if no published episodes exist
     */
    Integer findLatestPublishedEpisodeNumberByTitleId(UUID titleId);

    boolean existsById(UUID id);

    boolean existsByTitleIdAndSlug(UUID titleId, String slug);

    /**
     * Inspects entry IDs referencing a given video MediaAsset ID using a physical locking current-read.
     *
     * <p>Executes {@code SELECT id FROM screen_entries WHERE video_media_asset_id = ? FOR UPDATE}
     * ensuring latest committed data visibility under MySQL REPEATABLE READ.
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @return list of ScreenEntry UUIDs currently referencing the asset
     */
    List<UUID> findEntryIdsByVideoMediaAssetIdForUpdate(UUID videoMediaAssetId);

    /**
     * Inspects entry IDs referencing a given video MediaAsset ID using a non-locking read query.
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @return list of ScreenEntry UUIDs currently referencing the asset
     */
    List<UUID> findEntryIdsByVideoMediaAssetId(UUID videoMediaAssetId);

    /**
     * Retrieves the attached videoMediaAssetId for an entry using a lightweight scalar query.
     *
     * @param id ScreenEntry UUID
     * @return Optional containing the attached video MediaAsset UUID, or empty
     */
    Optional<UUID> findVideoMediaAssetIdById(UUID id);

    /**
     * Checks whether any ScreenEntry (other than excludingEntryId if non-null)
     * references the specified videoMediaAssetId where both the entry and its parent title are PUBLISHED.
     *
     * <p>Executes an InnoDB locking current-read across referencing entries.
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @param excludingEntryId optional ScreenEntry UUID to exclude from evaluation, or null
     * @return true if at least one other published reference under a published title exists, false otherwise
     */
    boolean hasOtherPublishedReference(UUID videoMediaAssetId, UUID excludingEntryId);
}
