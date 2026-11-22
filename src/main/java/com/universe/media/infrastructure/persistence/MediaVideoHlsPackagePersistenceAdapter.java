package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaVideoHlsPackagePersistenceAdapter implements MediaVideoHlsPackageRepositoryPort {

    private final SpringDataMediaVideoHlsPackageJpaRepository repository;

    public MediaVideoHlsPackagePersistenceAdapter(SpringDataMediaVideoHlsPackageJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataMediaVideoHlsPackageJpaRepository cannot be null.");
    }

    @Override
    public MediaVideoHlsPackage save(MediaVideoHlsPackage pkg) {
        Objects.requireNonNull(pkg, "MediaVideoHlsPackage cannot be null.");
        String id = pkg.getId().toString();

        MediaVideoHlsPackageJpaEntity entity;
        if (pkg.getPersistenceVersion() == null) {
            entity = new MediaVideoHlsPackageJpaEntity();
            entity.setId(id);
            entity.setAssetId(pkg.getAssetId().toString());
            entity.setSourceVersionId(pkg.getSourceVersionId().toString());
            entity.setSourceVersionNumber(pkg.getSourceVersionNumber());
            entity.setPackageUuid(pkg.getPackageUuid().toString());
            entity.setAttemptNumber(pkg.getAttemptNumber());
            entity.setStorageProviderId(pkg.getStorageProviderId().value());
            entity.setStorageBucketName(pkg.getStorageBucketName());
            entity.setBasePrefix(pkg.getBasePrefix().value());
            entity.setMasterManifestKey(pkg.getMasterManifestKey().value());
            entity.setHlsVersion(pkg.getHlsVersion());
            entity.setSegmentFormat(pkg.getSegmentFormat());
            entity.setSegmentDurationSeconds(pkg.getSegmentDurationSeconds());
            entity.setCreatedAt(pkg.getCreatedAt());
        } else {
            entity = repository.findById(id)
                    .orElseThrow(() -> new OptimisticLockingFailureException("HLS package not found for update: " + id));
            if (!Objects.equals(pkg.getPersistenceVersion(), entity.getPersistenceVersion())) {
                throw new OptimisticLockingFailureException(
                        "Concurrent modification detected on HLS package [" + id + "]: expected version "
                                + pkg.getPersistenceVersion() + ", found " + entity.getPersistenceVersion()
                );
            }
        }

        entity.setRenditionsJson(pkg.getRenditionsJson());
        entity.setMasterManifestSha256(pkg.getMasterManifestSha256() != null ? pkg.getMasterManifestSha256().value() : null);
        entity.setTotalSizeBytes(pkg.getTotalSizeBytes());
        entity.setObjectCount(pkg.getObjectCount());
        entity.setStatus(pkg.getStatus());
        entity.setFailureReason(pkg.getFailureReason());
        entity.setVerifiedAt(pkg.getVerifiedAt());
        entity.setReadyAt(pkg.getReadyAt());
        entity.setSupersededAt(pkg.getSupersededAt());
        entity.setPurgeAfter(pkg.getPurgeAfter());
        entity.setUpdatedAt(pkg.getUpdatedAt());

        MediaVideoHlsPackageJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<MediaVideoHlsPackage> findById(UUID id) {
        Objects.requireNonNull(id, "Package ID cannot be null.");
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaVideoHlsPackage> findByIdForUpdate(UUID id) {
        Objects.requireNonNull(id, "Package ID cannot be null.");
        return repository.findByIdForUpdate(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID sourceVersionId) {
        Objects.requireNonNull(sourceVersionId, "Source version ID cannot be null.");
        return repository.findBySourceVersionIdAndStatus(sourceVersionId.toString(), MediaVideoHlsPackageStatus.READY)
                .map(this::toDomain);
    }

    @Override
    public Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        return repository.findCurrentReadyByAssetId(assetId.toString()).map(this::toDomain);
    }

    @Override
    public List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID sourceVersionId) {
        Objects.requireNonNull(sourceVersionId, "Source version ID cannot be null.");
        return repository.findBySourceVersionIdForUpdateOrderByIdAsc(sourceVersionId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MediaVideoHlsPackage> findByAssetIdForUpdateOrderByIdAsc(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        return repository.findByAssetIdForUpdateOrderByIdAsc(assetId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MediaVideoHlsPackage> findByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        return repository.findByAssetId(assetId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MediaVideoHlsPackagePurgeCandidate> findPurgeEligibleCandidates(Instant now, int limit) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100: " + limit);
        }
        Instant terminalFloorCutoff = now.minus(MediaVideoHlsPackage.FAILED_RETENTION_GRACE);
        Instant supersededFloorCutoff = now.minus(MediaVideoHlsPackage.SUPERSEDED_RETENTION_GRACE);
        return repository.findPurgeEligibleCandidates(
                        now,
                        terminalFloorCutoff,
                        supersededFloorCutoff,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(p -> new MediaVideoHlsPackagePurgeCandidate(
                        UUID.fromString(p.getId()),
                        UUID.fromString(p.getAssetId())
                ))
                .toList();
    }

    @Override
    public boolean existsByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        return repository.existsByAssetId(assetId.toString());
    }

    private MediaVideoHlsPackage toDomain(MediaVideoHlsPackageJpaEntity e) {
        return MediaVideoHlsPackage.rehydrate(
                UUID.fromString(e.getId()),
                UUID.fromString(e.getAssetId()),
                UUID.fromString(e.getSourceVersionId()),
                e.getSourceVersionNumber(),
                UUID.fromString(e.getPackageUuid()),
                e.getAttemptNumber(),
                StorageProviderId.of(e.getStorageProviderId()),
                e.getStorageBucketName(),
                StorageKey.of(e.getBasePrefix()),
                StorageKey.of(e.getMasterManifestKey()),
                e.getHlsVersion(),
                e.getSegmentFormat(),
                e.getSegmentDurationSeconds(),
                e.getRenditionsJson(),
                e.getMasterManifestSha256() != null ? ContentHash.of(e.getMasterManifestSha256()) : null,
                e.getTotalSizeBytes(),
                e.getObjectCount(),
                e.getStatus(),
                e.getFailureReason(),
                e.getCreatedAt(),
                e.getVerifiedAt(),
                e.getReadyAt(),
                e.getSupersededAt(),
                e.getPurgeAfter(),
                e.getUpdatedAt(),
                e.getPersistenceVersion()
        );
    }
}
