package com.universe.media.application.ports;

import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Output port for persistent management of multipart media upload sessions.
 */
public interface MediaUploadSessionRepositoryPort {

    Optional<MediaUploadSession> findById(UUID id);

    Optional<MediaUploadSession> findByIdempotencyKey(String initiatorPrincipalId, String idempotencyKey);

    Optional<MediaUploadSession> findByStorageAllocationId(UUID storageAllocationId);

    Optional<MediaUploadSession> findByStorageLocation(StorageProviderId providerId, StorageKey storageKey);

    default Optional<MediaUploadSession> findCompletedSessionByAssetId(UUID assetId) {
        return Optional.empty();
    }

    MediaUploadSession save(MediaUploadSession session);

    /**
     * Maximum allowed batch size for bounded query execution.
     */
    int MAX_BATCH_SIZE = 100;

    /**
     * Approved lifecycle statuses permitted for cleanup candidate discovery.
     * All active states, completed states, and ambiguous failure states (such as FAILED)
     * are strictly prohibited from candidate discovery and reserved for reconciliation.
     */
    Set<MediaUploadSessionStatus> APPROVED_CLEANUP_CANDIDATE_STATUSES = Set.of(
            MediaUploadSessionStatus.ABORTED,
            MediaUploadSessionStatus.EXPIRED
    );

    Set<MediaUploadSessionStatus> DEFAULT_CLEANUP_CANDIDATE_STATUSES = APPROVED_CLEANUP_CANDIDATE_STATUSES;

    /**
     * Approved cleanup statuses permitted for cleanup candidate discovery.
     * Only unattempted (PENDING) or previously failed (FAILED) cleanup states are eligible candidates.
     * NONE, COMPLETED, and IN_PROGRESS are strictly prohibited.
     */
    Set<UploadSessionCleanupStatus> APPROVED_CLEANUP_DISCOVERY_STATUSES = Set.of(
            UploadSessionCleanupStatus.PENDING,
            UploadSessionCleanupStatus.FAILED
    );

    Set<UploadSessionCleanupStatus> APPROVED_CLEANUP_STATUSES = APPROVED_CLEANUP_DISCOVERY_STATUSES;

    Set<UploadSessionCleanupStatus> DEFAULT_CLEANUP_STATUSES = APPROVED_CLEANUP_DISCOVERY_STATUSES;

    /**
     * Finds upload sessions eligible for cleanup candidate discovery.
     * <p>
     * <strong>CRITICAL SAFETY GUARANTEE:</strong> Candidate discovery is NOT authorization to perform destructive
     * provider cleanup or data deletion. Discovered sessions are merely candidates meeting filtering criteria
     * (unattempted PENDING or previously FAILED cleanup under safe lifecycle statuses ABORTED or EXPIRED
     * without persisted completion intent). Discovered sessions must undergo explicit domain authorization
     * and safety verification before any destructive provider operations (such as aborting provider multipart uploads
     * or releasing storage allocations) are performed.
     *
     * @param sessionStatuses  session lifecycle statuses to include (must be a subset of APPROVED_CLEANUP_CANDIDATE_STATUSES)
     * @param cleanupStatuses  cleanup statuses to include (must be a subset of APPROVED_CLEANUP_DISCOVERY_STATUSES)
     * @param maxRetryCount    maximum allowed retry count (inclusive)
     * @param maxLastAttemptAt upper bound for last cleanup attempt timestamp, or null for any/unattempted
     * @param limit            maximum number of sessions to return (bounded batch, 1..MAX_BATCH_SIZE)
     * @return bounded list of matching upload sessions
     */
    List<MediaUploadSession> findSessionsEligibleForCleanup(
            Set<MediaUploadSessionStatus> sessionStatuses,
            Set<UploadSessionCleanupStatus> cleanupStatuses,
            int maxRetryCount,
            Instant maxLastAttemptAt,
            int limit
    );

    default List<MediaUploadSession> findSessionsEligibleForCleanup(int maxRetryCount, Instant maxLastAttemptAt, int limit) {
        return findSessionsEligibleForCleanup(
                DEFAULT_CLEANUP_CANDIDATE_STATUSES,
                DEFAULT_CLEANUP_STATUSES,
                maxRetryCount,
                maxLastAttemptAt,
                limit
        );
    }

    /**
     * Finds upload sessions that have passed their hard expiration timestamp.
     *
     * @param statuses eligible session statuses (e.g. ALLOCATED, INITIATED)
     * @param now      current time cutoff
     * @param limit    maximum number of sessions to return (bounded batch)
     * @return bounded list of matching upload sessions
     */
    List<MediaUploadSession> findExpiredSessions(
            Set<MediaUploadSessionStatus> statuses,
            Instant now,
            int limit
    );

    default List<MediaUploadSession> findExpiredSessions(Instant now, int limit) {
        return findExpiredSessions(
                Set.of(MediaUploadSessionStatus.ALLOCATED, MediaUploadSessionStatus.INITIATED),
                now,
                limit
        );
    }

    /**
     * Finds upload sessions with active leases that have expired.
     *
     * @param statuses eligible session statuses (e.g. ALLOCATED, COMPLETING)
     * @param now      current time cutoff
     * @param limit    maximum number of sessions to return (bounded batch)
     * @return bounded list of matching upload sessions
     */
    List<MediaUploadSession> findSessionsWithExpiredLeases(
            Set<MediaUploadSessionStatus> statuses,
            Instant now,
            int limit
    );

    default List<MediaUploadSession> findSessionsWithExpiredLeases(Instant now, int limit) {
        return findSessionsWithExpiredLeases(
                Set.of(MediaUploadSessionStatus.ALLOCATED, MediaUploadSessionStatus.COMPLETING),
                now,
                limit
        );
    }

    /**
     * Finds completed upload sessions for an initiator with bounded pagination and optional mediaType filter.
     *
     * @param initiatorPrincipalId authoritative initiator principal ID
     * @param consumerContext consumer context filter (e.g. "screen")
     * @param targetEntityType target entity type filter (e.g. "SCREEN_ENTRY")
     * @param mediaType optional media type filter (e.g. "VIDEO"), or null for any
     * @param targetEntityId optional target entity UUID filter, or null for actor-wide discovery
     * @param page zero-based page index
     * @param size page size (1..50)
     * @return paginated session container
     */
    default MediaUploadSessionPage findCompletedSessions(
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            String mediaType,
            UUID targetEntityId,
            int page,
            int size
    ) {
        throw new UnsupportedOperationException("findCompletedSessions is not supported by this repository implementation.");
    }

    /**
     * Finds completed upload sessions for an initiator with bounded pagination.
     *
     * @param initiatorPrincipalId authoritative initiator principal ID
     * @param consumerContext consumer context filter (e.g. "screen")
     * @param targetEntityType target entity type filter (e.g. "SCREEN_ENTRY")
     * @param targetEntityId optional target entity UUID filter, or null for actor-wide discovery
     * @param page zero-based page index
     * @param size page size (1..50)
     * @return paginated session container
     */
    default MediaUploadSessionPage findCompletedSessions(
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            int page,
            int size
    ) {
        return findCompletedSessions(initiatorPrincipalId, consumerContext, targetEntityType, null, targetEntityId, page, size);
    }
}
