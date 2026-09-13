package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaImageVariantPersistenceAdapter implements MediaImageVariantRepositoryPort {

    private final SpringDataMediaImageVariantJpaRepository repository;

    public MediaImageVariantPersistenceAdapter(
            SpringDataMediaImageVariantJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null.");
    }

    @Override
    public MediaImageVariant save(
            MediaImageVariant variant
    ) {
        Objects.requireNonNull(
                variant,
                "Media image variant cannot be null."
        );

        MediaImageVariantJpaEntity entity = new MediaImageVariantJpaEntity();
        entity.setId(variant.getId().toString());
        entity.setVersionId(variant.getVersionId().toString());
        entity.setVariantKey(variant.getVariantKey());
        entity.setTargetWidth(variant.getTargetWidth());
        entity.setStorageProviderId(variant.getStorageLocation().providerId().value());
        entity.setStorageKey(variant.getStorageLocation().key().value());
        entity.setContentHash(variant.getContentHash().value());
        entity.setMimeType(variant.getMimeType().value());
        entity.setSizeBytes(variant.getSizeBytes());
        entity.setWidth(variant.getWidth());
        entity.setHeight(variant.getHeight());
        entity.setCreatedAt(variant.getCreatedAt());

        MediaImageVariantJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<MediaImageVariant> findByVersionIdAndVariantKey(
            UUID versionId,
            String variantKey
    ) {
        Objects.requireNonNull(
                versionId,
                "Version ID cannot be null."
        );
        Objects.requireNonNull(
                variantKey,
                "Variant key cannot be null."
        );

        return repository.findByVersionIdAndVariantKey(
                versionId.toString(),
                variantKey
        ).map(this::toDomain);
    }

    @Override
    public boolean existsByVersionIdAndVariantKey(
            UUID versionId,
            String variantKey
    ) {
        Objects.requireNonNull(
                versionId,
                "Version ID cannot be null."
        );
        Objects.requireNonNull(
                variantKey,
                "Variant key cannot be null."
        );

        return repository.existsByVersionIdAndVariantKey(
                versionId.toString(),
                variantKey
        );
    }

    @Override
    public boolean existsByStorageLocation(
            StorageLocation storageLocation
    ) {
        Objects.requireNonNull(
                storageLocation,
                "Storage location cannot be null."
        );

        return repository.existsByStorageProviderIdAndStorageKey(
                storageLocation.providerId().value(),
                storageLocation.key().value()
        );
    }

    @Override
    public List<MediaImageVariant> findAllByVersionId(
            UUID versionId
    ) {
        Objects.requireNonNull(
                versionId,
                "Version ID cannot be null."
        );

        return repository.findByVersionId(versionId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MediaImageVariant> findAllByVersionIds(
            Collection<UUID> versionIds
    ) {
        Objects.requireNonNull(
                versionIds,
                "Version IDs cannot be null."
        );

        if (versionIds.isEmpty()) {
            return List.of();
        }

        List<String> stringIds = versionIds.stream().map(UUID::toString).toList();
        return repository.findByVersionIdIn(stringIds)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void deleteByVersionId(
            UUID versionId
    ) {
        Objects.requireNonNull(
                versionId,
                "Version ID cannot be null."
        );

        repository.deleteByVersionId(versionId.toString());
    }

    @Override
    public void deleteByVersionIds(
            Collection<UUID> versionIds
    ) {
        Objects.requireNonNull(
                versionIds,
                "Version IDs cannot be null."
        );

        if (versionIds.isEmpty()) {
            return;
        }

        List<String> stringIds = versionIds.stream().map(UUID::toString).toList();
        repository.deleteByVersionIdIn(stringIds);
    }

    private MediaImageVariant toDomain(
            MediaImageVariantJpaEntity entity
    ) {
        return MediaImageVariant.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getVersionId()),
                entity.getVariantKey(),
                entity.getTargetWidth(),
                StorageLocation.of(entity.getStorageProviderId(), entity.getStorageKey()),
                ContentHash.of(entity.getContentHash()),
                MimeType.of(entity.getMimeType()),
                entity.getSizeBytes(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getCreatedAt()
        );
    }
}
