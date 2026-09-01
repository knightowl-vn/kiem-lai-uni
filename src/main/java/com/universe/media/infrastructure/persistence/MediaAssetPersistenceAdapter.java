package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaAssetPersistenceAdapter implements MediaAssetRepositoryPort {

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
        } else {
            entity = new MediaAssetJpaEntity();
            entity.setId(assetId);
            entity.setMediaType(asset.getMediaType().name());
            entity.setVisibility(asset.getVisibility().name());
            entity.setStatus(asset.getStatus().name());
            entity.setCurrentVersionNumber(asset.getCurrentVersionNumber());
            entity.setCreatedAt(asset.getCreatedAt());
            entity.setUpdatedAt(asset.getUpdatedAt());
        }

        MediaAssetJpaEntity savedEntity = repository.save(entity);
        return toDomain(savedEntity);
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
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
