package com.universe.media.application.ports;

import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.StorageLocation;

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
}
