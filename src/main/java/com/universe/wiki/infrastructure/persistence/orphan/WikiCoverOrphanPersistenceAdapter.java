package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.wiki.application.exceptions.WikiCoverMediaAssetDeletingException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import org.springframework.context.annotation.Lazy;
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
 * under standard transaction boundaries with durable DELETING fence and Reference-Attach Gate.
 */
@Component
public class WikiCoverOrphanPersistenceAdapter implements WikiCoverOrphanRepositoryPort {

    public static final int MAX_ERROR_LENGTH = 500;

    private final SpringDataWikiCoverOrphanJpaRepository repository;
    private final WikiArticleRepositoryPort articleRepositoryPort;

    public WikiCoverOrphanPersistenceAdapter(
            SpringDataWikiCoverOrphanJpaRepository repository,
            @Lazy WikiArticleRepositoryPort articleRepositoryPort
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataWikiCoverOrphanJpaRepository cannot be null.");
        this.articleRepositoryPort = Objects.requireNonNull(articleRepositoryPort, "WikiArticleRepositoryPort cannot be null.");
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
    @Transactional
    public ClearReferencedOrphanResult clearNonDeletingOrphanForReference(UUID mediaAssetId) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");

        String assetIdStr = mediaAssetId.toString();
        int deleted = repository.deleteNonDeletingByMediaAssetId(assetIdStr);
        if (deleted > 0) {
            return ClearReferencedOrphanResult.CLEARED;
        }

        if (repository.existsById(assetIdStr)) {
            return ClearReferencedOrphanResult.DELETING_PRESERVED;
        }

        return ClearReferencedOrphanResult.ABSENT;
    }

    @Override
    @Transactional
    public void coordinateCoverAttachment(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        String assetIdStr = mediaAssetId.toString();
        if (!repository.existsById(assetIdStr)) {
            return;
        }

        Optional<WikiCoverOrphanJpaEntity> entityOpt = repository.findByMediaAssetIdForUpdate(assetIdStr);
        if (entityOpt.isEmpty()) {
            return;
        }

        WikiCoverOrphanJpaEntity entity = entityOpt.get();
        if (entity.getStatus() == WikiCoverOrphanStatus.DELETING) {
            throw new WikiCoverMediaAssetDeletingException(mediaAssetId);
        }

        repository.delete(entity);
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
    @Transactional
    public PrepareDeletionFenceResult prepareDeletionFence(UUID mediaAssetId, UUID claimToken, Instant now) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");
        Objects.requireNonNull(now, "Now timestamp cannot be null.");

        Optional<WikiCoverOrphanJpaEntity> entityOpt = repository.findByMediaAssetIdForUpdate(mediaAssetId.toString());
        if (entityOpt.isEmpty()) {
            return PrepareDeletionFenceResult.LOST_OWNERSHIP;
        }

        WikiCoverOrphanJpaEntity entity = entityOpt.get();
        if (entity.getStatus() != WikiCoverOrphanStatus.PROCESSING
                || entity.getClaimToken() == null
                || !claimToken.toString().equals(entity.getClaimToken())) {
            return PrepareDeletionFenceResult.LOST_OWNERSHIP;
        }

        if (articleRepositoryPort.hasCoverReference(mediaAssetId)) {
            repository.delete(entity);
            return PrepareDeletionFenceResult.REFERENCED;
        }

        entity.setStatus(WikiCoverOrphanStatus.DELETING);
        entity.setUpdatedAt(now);
        repository.save(entity);

        return PrepareDeletionFenceResult.FENCED_FOR_DELETION;
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
    public boolean deleteClaimedDeletingEpoch(UUID mediaAssetId, UUID claimToken) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");

        return repository.deleteClaimedDeletingEpoch(mediaAssetId.toString(), claimToken.toString()) > 0;
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
    public boolean releaseDeletingClaimForRetry(UUID mediaAssetId, UUID claimToken, String lastError, Instant now) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");
        Objects.requireNonNull(now, "Now timestamp cannot be null.");

        String sanitizedError = sanitizeLastError(lastError);
        return repository.releaseDeletingClaimForRetry(
                mediaAssetId.toString(),
                claimToken.toString(),
                sanitizedError,
                now
        ) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiCoverOrphanRecord> findStaleDeletingCandidates(Instant leaseCutoff, int limit) {
        Objects.requireNonNull(leaseCutoff, "Lease cutoff cannot be null.");
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be greater than zero: " + limit);
        }

        return repository.findStaleDeletingCandidates(leaseCutoff, PageRequest.of(0, limit))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    @Transactional
    public boolean claimDeletingForRetry(UUID mediaAssetId, Instant leaseCutoff, UUID claimToken, Instant now) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(leaseCutoff, "Lease cutoff cannot be null.");
        Objects.requireNonNull(claimToken, "Claim token cannot be null.");
        Objects.requireNonNull(now, "Now timestamp cannot be null.");

        return repository.claimDeletingForRetry(
                mediaAssetId.toString(),
                leaseCutoff,
                claimToken.toString(),
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
