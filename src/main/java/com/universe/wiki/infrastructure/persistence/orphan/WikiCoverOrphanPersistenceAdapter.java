package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link WikiCoverOrphanRepositoryPort}.
 *
 * <p>Coordinates atomic conditional database operations for Wiki cover orphan reconciliation
 * under standard transaction boundaries.
 */
@Component
public class WikiCoverOrphanPersistenceAdapter implements WikiCoverOrphanRepositoryPort {

    public static final int MAX_ERROR_LENGTH = 500;

    private final SpringDataWikiCoverOrphanJpaRepository repository;

    public WikiCoverOrphanPersistenceAdapter(SpringDataWikiCoverOrphanJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataWikiCoverOrphanJpaRepository cannot be null.");
    }

    @Override
    @Transactional
    public void recordOrphanObservation(UUID mediaAssetId, Instant observedAt) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(observedAt, "Observed timestamp cannot be null.");

        repository.insertObservationIfAbsent(mediaAssetId.toString(), observedAt);
    }

    @Override
    @Transactional
    public boolean deleteByMediaAssetId(UUID mediaAssetId) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");

        return repository.deleteByMediaAssetId(mediaAssetId.toString()) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiCoverOrphanRecord> findEligiblePendingCandidates(Instant graceCutoff, int limit) {
        Objects.requireNonNull(graceCutoff, "Grace cutoff cannot be null.");
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be greater than zero: " + limit);
        }

        return repository.findEligiblePending(graceCutoff, PageRequest.of(0, limit))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    @Transactional
    public boolean claimIfEligible(UUID mediaAssetId, Instant graceCutoff, UUID claimToken, Instant now) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(graceCutoff, "Grace cutoff cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");
        Objects.requireNonNull(now, "Now timestamp cannot be null.");

        return repository.claimIfEligible(
                mediaAssetId.toString(),
                graceCutoff,
                claimToken.toString(),
                now
        ) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isClaimOwned(UUID mediaAssetId, UUID claimToken) {
        if (mediaAssetId == null || claimToken == null) {
            return false;
        }

        return repository.isClaimOwned(mediaAssetId.toString(), claimToken.toString());
    }

    @Override
    @Transactional
    public boolean deleteClaimedEpoch(UUID mediaAssetId, UUID claimToken) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");

        return repository.deleteClaimedEpoch(mediaAssetId.toString(), claimToken.toString()) > 0;
    }

    @Override
    @Transactional
    public boolean releaseClaimForRetry(UUID mediaAssetId, UUID claimToken, String lastError, Instant now) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");
        Objects.requireNonNull(now, "Now timestamp cannot be null.");

        String sanitizedError = sanitizeLastError(lastError);
        return repository.releaseClaimForRetry(
                mediaAssetId.toString(),
                claimToken.toString(),
                sanitizedError,
                now
        ) > 0;
    }

    @Override
    @Transactional
    public int recoverStaleProcessing(Instant leaseCutoff, Instant now) {
        Objects.requireNonNull(leaseCutoff, "Lease cutoff cannot be null.");
        Objects.requireNonNull(now, "Now timestamp cannot be null.");

        return repository.recoverStaleProcessing(leaseCutoff, now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WikiCoverOrphanRecord> findByMediaAssetId(UUID mediaAssetId) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");

        return repository.findByMediaAssetId(mediaAssetId.toString()).map(this::toRecord);
    }

    private WikiCoverOrphanRecord toRecord(WikiCoverOrphanJpaEntity entity) {
        return new WikiCoverOrphanRecord(
                UUID.fromString(entity.getMediaAssetId()),
                entity.getStatus(),
                entity.getFirstSeenOrphanAt(),
                entity.getClaimToken() != null ? UUID.fromString(entity.getClaimToken()) : null,
                entity.getLockedAt(),
                entity.getRetryCount(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String sanitizeLastError(String lastError) {
        if (lastError == null) {
            return null;
        }
        String trimmed = lastError.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_ERROR_LENGTH
                ? trimmed.substring(0, MAX_ERROR_LENGTH)
                : trimmed;
    }
}
