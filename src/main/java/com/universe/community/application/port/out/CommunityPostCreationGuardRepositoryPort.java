package com.universe.community.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Application port for Community post creation guard persistence.
 * Provides multi-instance same-author locking and append/query operations
 * on the immutable creation event ledger.
 */
public interface CommunityPostCreationGuardRepositoryPort {

    /**
     * Acquires an exclusive pessimistic database write lock for the given author.
     * Ensures the guard row exists and is locked within the current transaction.
     *
     * @param authorUserId the author's UUID
     */
    void acquireAuthorLock(UUID authorUserId);

    /**
     * Finds the timestamp of the latest successful post creation by this author.
     *
     * @param authorUserId the author's UUID
     * @return optional timestamp of the latest successful creation
     */
    Optional<Instant> findLatestCreationTimestamp(UUID authorUserId);

    /**
     * Counts the number of creations by this author strictly after the given cutoff.
     *
     * @param authorUserId the author's UUID
     * @param cutoff the exclusive lower boundary timestamp
     * @return count of creations after cutoff
     */
    long countCreationsAfter(UUID authorUserId, Instant cutoff);

    /**
     * Finds the timestamp of the oldest creation event by this author strictly after the cutoff.
     *
     * @param authorUserId the author's UUID
     * @param cutoff the exclusive lower boundary timestamp
     * @return optional timestamp of the oldest creation after cutoff
     */
    Optional<Instant> findOldestCreationTimestampAfter(UUID authorUserId, Instant cutoff);

    /**
     * Finds the latest matching creation timestamp for the given author and normalized caption hash
     * created strictly after the given cutoff.
     *
     * @param authorUserId the author's UUID
     * @param normalizedCaptionHash the 64-character lowercase SHA-256 hash of the normalized caption
     * @param cutoff the exclusive lower boundary timestamp
     * @return optional latest matching timestamp after cutoff
     */
    Optional<Instant> findLatestMatchingCaptionCreation(UUID authorUserId, String normalizedCaptionHash, Instant cutoff);

    /**
     * Appends an immutable creation event record in the same transaction as post persistence.
     *
     * @param eventId unique event UUID
     * @param authorUserId author's UUID
     * @param postId created post's UUID
     * @param normalizedCaptionHash 64-character lowercase SHA-256 hash of the normalized caption
     * @param createdAt creation timestamp
     */
    void appendCreationEvent(
            UUID eventId,
            UUID authorUserId,
            UUID postId,
            String normalizedCaptionHash,
            Instant createdAt
    );
}
