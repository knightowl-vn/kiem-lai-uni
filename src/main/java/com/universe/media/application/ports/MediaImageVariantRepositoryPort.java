package com.universe.media.application.ports;

import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.StorageLocation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaImageVariantRepositoryPort {

    MediaImageVariant save(
            MediaImageVariant variant
    );

    Optional<MediaImageVariant> findByVersionIdAndVariantKey(
            UUID versionId,
            String variantKey
    );

    boolean existsByVersionIdAndVariantKey(
            UUID versionId,
            String variantKey
    );

    boolean existsByStorageLocation(
            StorageLocation storageLocation
    );

    List<MediaImageVariant> findAllByVersionId(
            UUID versionId
    );

    List<MediaImageVariant> findAllByVersionIds(
            Collection<UUID> versionIds
    );

    void deleteByVersionId(
            UUID versionId
    );

    void deleteByVersionIds(
            Collection<UUID> versionIds
    );
}
