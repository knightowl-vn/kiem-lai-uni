package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaVideoPackagingJobCandidate;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaVideoPackagingJobPersistenceAdapter implements MediaVideoPackagingJobRepositoryPort {

    private final SpringDataMediaVideoPackagingJobJpaRepository repository;

    public MediaVideoPackagingJobPersistenceAdapter(SpringDataMediaVideoPackagingJobJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataMediaVideoPackagingJobJpaRepository cannot be null.");
    }

    @Override
    public MediaVideoPackagingJob save(MediaVideoPackagingJob job) {
        Objects.requireNonNull(job, "MediaVideoPackagingJob cannot be null.");
        String id = job.getId().toString();

        MediaVideoPackagingJobJpaEntity entity;
        if (job.getPersistenceVersion() == null) {
            entity = new MediaVideoPackagingJobJpaEntity();
            entity.setId(id);
            entity.setAssetId(job.getAssetId().toString());
            entity.setSourceVersionId(job.getSourceVersionId().toString());
            entity.setSourceVersionNumber(job.getSourceVersionNumber());
            entity.setMaxAttempts(job.getMaxAttempts());
            entity.setCreatedAt(job.getCreatedAt());
        } else {
            entity = repository.findById(id)
                    .orElseThrow(() -> new OptimisticLockingFailureException("Packaging job not found for update: " + id));
            if (!Objects.equals(job.getPersistenceVersion(), entity.getPersistenceVersion())) {
                throw new OptimisticLockingFailureException(
                        "Concurrent modification detected on packaging job [" + id + "]: expected version "
                                + job.getPersistenceVersion() + ", found " + entity.getPersistenceVersion()
                );
            }
        }

        entity.setStatus(job.getStatus());
        entity.setLeaseGeneration(job.getLeaseGeneration());
        entity.setCurrentPackageId(job.getCurrentPackageId() != null ? job.getCurrentPackageId().toString() : null);
        entity.setAttemptCount(job.getAttemptCount());
        entity.setLeaseOwner(job.getLeaseOwner());
        entity.setLeaseExpiresAt(job.getLeaseExpiresAt());
        entity.setHeartbeatAt(job.getHeartbeatAt());
        entity.setNextAttemptAt(job.getNextAttemptAt());
        entity.setLastError(job.getLastError());
        entity.setUpdatedAt(job.getUpdatedAt());
        entity.setCompletedAt(job.getCompletedAt());

        MediaVideoPackagingJobJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<MediaVideoPackagingJob> findById(UUID id) {
        Objects.requireNonNull(id, "Job ID cannot be null.");
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaVideoPackagingJob> findByIdForUpdate(UUID id) {
        Objects.requireNonNull(id, "Job ID cannot be null.");
        return repository.findByIdForUpdate(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaVideoPackagingJob> findActiveBySourceVersionIdForUpdate(UUID sourceVersionId) {
        Objects.requireNonNull(sourceVersionId, "Source version ID cannot be null.");
        return repository.findActiveBySourceVersionIdForUpdate(sourceVersionId.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID sourceVersionId) {
        Objects.requireNonNull(sourceVersionId, "Source version ID cannot be null.");
        List<MediaVideoPackagingJobStatus> statuses = repository.findDistinctTerminalStatusesBySourceVersionId(
                sourceVersionId.toString()
        );
        if (statuses.contains(MediaVideoPackagingJobStatus.SUCCEEDED)) {
            return Optional.of(MediaVideoPackagingJobStatus.SUCCEEDED);
        }
        if (statuses.contains(MediaVideoPackagingJobStatus.FAILED)) {
            return Optional.of(MediaVideoPackagingJobStatus.FAILED);
        }
        if (statuses.contains(MediaVideoPackagingJobStatus.CANCELLED)) {
            return Optional.of(MediaVideoPackagingJobStatus.CANCELLED);
        }
        return Optional.empty();
    }

    @Override
    public List<MediaVideoPackagingJob> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        return repository.findByAssetIdForUpdateOrderByIdAsc(assetId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MediaVideoPackagingJobCandidate> findClaimableCandidates(Instant now, int limit) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100: " + limit);
        }
        return repository.findClaimableCandidates(now, PageRequest.of(0, limit))
                .stream()
                .map(p -> new MediaVideoPackagingJobCandidate(UUID.fromString(p.getId()), UUID.fromString(p.getAssetId())))
                .toList();
    }

    @Override
    public int updateHeartbeatIfLeaseMatches(
            UUID jobId,
            String leaseOwner,
            long leaseGeneration,
            Instant now,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            Instant updatedAt
    ) {
        Objects.requireNonNull(jobId, "Job ID cannot be null.");
        Objects.requireNonNull(leaseOwner, "Lease owner cannot be null.");
        Objects.requireNonNull(now, "Validation timestamp cannot be null.");
        Objects.requireNonNull(heartbeatAt, "Heartbeat timestamp cannot be null.");
        Objects.requireNonNull(leaseExpiresAt, "Lease expiry timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");
        return repository.updateHeartbeatIfLeaseMatches(
                jobId.toString(),
                leaseOwner.trim(),
                leaseGeneration,
                now,
                heartbeatAt,
                leaseExpiresAt,
                updatedAt
        );
    }

    @Override
    public boolean existsByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        return repository.existsByAssetId(assetId.toString());
    }

    private MediaVideoPackagingJob toDomain(MediaVideoPackagingJobJpaEntity e) {
        return MediaVideoPackagingJob.rehydrate(
                UUID.fromString(e.getId()),
                UUID.fromString(e.getAssetId()),
                UUID.fromString(e.getSourceVersionId()),
                e.getSourceVersionNumber(),
                e.getStatus(),
                e.getLeaseGeneration(),
                e.getCurrentPackageId() != null ? UUID.fromString(e.getCurrentPackageId()) : null,
                e.getAttemptCount(),
                e.getMaxAttempts(),
                e.getLeaseOwner(),
                e.getLeaseExpiresAt(),
                e.getHeartbeatAt(),
                e.getNextAttemptAt(),
                e.getLastError(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getCompletedAt(),
                e.getPersistenceVersion()
        );
    }
}
