package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaAssetVersionPersistenceAdapter implements MediaAssetVersionRepositoryPort {

    private final SpringDataMediaAssetVersionJpaRepository repository;

    public MediaAssetVersionPersistenceAdapter(
            SpringDataMediaAssetVersionJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public MediaAssetVersion save(
            MediaAssetVersion version
    ) {
        Objects.requireNonNull(
                version,
                "Media asset version cannot be null."
        );

        MediaAssetVersionJpaEntity entity = new MediaAssetVersionJpaEntity();
        entity.setId(version.getId().toString());
        entity.setAssetId(version.getAssetId().toString());
        entity.setVersionNumber(version.getVersionNumber());
        entity.setStorageProviderId(version.getStorageLocation().providerId().value());
        entity.setStorageKey(version.getStorageLocation().key().value());
        entity.setPublicUrl(version.getPublicUrl());
        entity.setContentHash(version.getContentHash().value());
        entity.setMimeType(version.getMimeType().value());
        entity.setSizeBytes(version.getSizeBytes());
        entity.setOriginalFilename(version.getOriginalFilename());
        entity.setCreatedAt(version.getCreatedAt());

        MediaAssetVersionJpaEntity savedEntity = repository.save(entity);
        return toDomain(savedEntity);
    }

    @Override
    public Optional<MediaAssetVersion> findByAssetIdAndVersionNumber(
            UUID assetId,
            int versionNumber
    ) {
        Objects.requireNonNull(
                assetId,
                "Media asset ID cannot be null."
        );

        return repository.findByAssetIdAndVersionNumber(
                assetId.toString(),
                versionNumber
        ).map(this::toDomain);
    }

    @Override
    public boolean existsByStorageLocation(
            StorageLocation location
    ) {
        Objects.requireNonNull(
                location,
                "Storage location cannot be null."
        );

        return repository.existsByStorageProviderIdAndStorageKey(
                location.providerId().value(),
                location.key().value()
        );
    }

    @Override
    public List<MediaAssetVersion> findByContentHash(
            ContentHash contentHash
    ) {
        Objects.requireNonNull(
                contentHash,
                "Content hash cannot be null."
        );

        return repository.findByContentHash(contentHash.value())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private MediaAssetVersion toDomain(
            MediaAssetVersionJpaEntity entity
    ) {
        return MediaAssetVersion.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getAssetId()),
                entity.getVersionNumber(),
                StorageLocation.of(entity.getStorageProviderId(), entity.getStorageKey()),
                entity.getPublicUrl(),
                ContentHash.of(entity.getContentHash()),
                MimeType.of(entity.getMimeType()),
                entity.getSizeBytes(),
                entity.getOriginalFilename(),
                entity.getCreatedAt()
        );
    }
}
