package com.universe.media.application.ports;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.StorageLocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetVersionRepositoryPort {

    MediaAssetVersion save(
            MediaAssetVersion version
    );

    Optional<MediaAssetVersion> findByAssetIdAndVersionNumber(
            UUID assetId,
            int versionNumber
    );

    boolean existsByStorageLocation(
            StorageLocation storageLocation
    );

    List<MediaAssetVersion> findByContentHash(
            ContentHash contentHash
    );
}
