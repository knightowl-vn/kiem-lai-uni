package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaAssetPersistenceAdapter implements MediaAssetRepositoryPort {

    public static final int MAX_PAGE_SIZE = 100;

    private final SpringDataMediaAssetJpaRepository repository;

    public MediaAssetPersistenceAdapter(
            SpringDataMediaAssetJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Optional<MediaAsset> findById(
            UUID id
    ) {
        Objects.requireNonNull(
                id,
                "Media asset ID cannot be null."
        );

        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaAsset> findByIdForUpdate(
            UUID id
    ) {
        Objects.requireNonNull(
                id,
                "Media asset ID cannot be null."
        );

        return repository.findByIdForUpdate(id.toString()).map(this::toDomain);
    }

    @Override
    public MediaAsset save(
            MediaAsset asset
    ) {
        Objects.requireNonNull(
                asset,
                "Media asset cannot be null."
        );

        String assetId = asset.getId().toString();
        Optional<MediaAssetJpaEntity> existingOpt = repository.findById(assetId);

        MediaAssetJpaEntity entity;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();
            entity.setVisibility(asset.getVisibility().name());
            entity.setStatus(asset.getStatus().name());
            entity.setCurrentVersionNumber(asset.getCurrentVersionNumber());
            entity.setUpdatedAt(asset.getUpdatedAt());
            entity.setClientTag(asset.getClientTag());
        } else {
            entity = new MediaAssetJpaEntity();
            entity.setId(assetId);
            entity.setMediaType(asset.getMediaType().name());
            entity.setVisibility(asset.getVisibility().name());
            entity.setStatus(asset.getStatus().name());
            entity.setCurrentVersionNumber(asset.getCurrentVersionNumber());
            entity.setCreatedAt(asset.getCreatedAt());
            entity.setUpdatedAt(asset.getUpdatedAt());
            entity.setClientTag(asset.getClientTag());
        }

        MediaAssetJpaEntity savedEntity = repository.save(entity);
        return toDomain(savedEntity);
    }

    @Override
    public void deleteById(
            UUID id
    ) {
        Objects.requireNonNull(
                id,
                "Media asset ID cannot be null."
        );

        repository.deleteById(id.toString());
    }

    @Override
    public List<MediaAsset> findExpiredDeleted(
            Instant cutoff,
            int limit
    ) {
        Objects.requireNonNull(
                cutoff,
                "Cutoff timestamp cannot be null."
        );

        if (limit <= 0) {
            throw new IllegalArgumentException("Query limit must be greater than zero: " + limit);
        }

        return repository.findExpiredDeleted(cutoff, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MediaAssetCandidate> findActiveByClientTagKeyset(
            String clientTag,
            Instant upperBound,
            Instant lastCreatedAt,
            UUID lastAssetId,
            int limit
    ) {
        Objects.requireNonNull(
                clientTag,
                "Client tag cannot be null."
        );
        Objects.requireNonNull(
                upperBound,
                "Upper bound timestamp cannot be null."
        );

        if (limit <= 0) {
            throw new IllegalArgumentException("Query limit must be greater than zero: " + limit);
        }
        if (limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Query limit cannot exceed " + MAX_PAGE_SIZE + ": " + limit
            );
        }

        Pageable pageable = PageRequest.of(0, limit);
        List<MediaAssetCandidateProjection> projections;

        if (lastCreatedAt == null && lastAssetId == null) {
            projections = repository.findActiveByClientTagFirstPage(
                    clientTag,
                    upperBound,
                    pageable
            );
        } else if (lastCreatedAt != null && lastAssetId != null) {
            projections = repository.findActiveByClientTagSubsequentPage(
                    clientTag,
                    upperBound,
                    lastCreatedAt,
                    lastAssetId.toString(),
                    pageable
            );
        } else {
            throw new IllegalArgumentException(
                    "Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null."
            );
        }

        return projections.stream()
                .map(p -> new MediaAssetCandidate(UUID.fromString(p.getId()), p.getCreatedAt()))
                .toList();
    }

    private MediaAsset toDomain(
            MediaAssetJpaEntity entity
    ) {
        return MediaAsset.rehydrate(
                UUID.fromString(entity.getId()),
                MediaType.valueOf(entity.getMediaType()),
                MediaVisibility.valueOf(entity.getVisibility()),
                MediaAssetStatus.valueOf(entity.getStatus()),
                entity.getCurrentVersionNumber(),
                entity.getClientTag(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
