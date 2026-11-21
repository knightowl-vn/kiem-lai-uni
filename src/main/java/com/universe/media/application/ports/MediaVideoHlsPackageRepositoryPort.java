package com.universe.media.application.ports;

import com.universe.media.domain.MediaVideoHlsPackage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaVideoHlsPackageRepositoryPort {

    MediaVideoHlsPackage save(MediaVideoHlsPackage pkg);

    Optional<MediaVideoHlsPackage> findById(UUID id);

    Optional<MediaVideoHlsPackage> findByIdForUpdate(UUID id);

    Optional<MediaVideoHlsPackage> findReadyBySourceVersionId(UUID sourceVersionId);

    /**
     * Resolves the READY HLS package belonging exclusively to the asset's current authoritative version number.
     */
    Optional<MediaVideoHlsPackage> findCurrentReadyByAssetId(UUID assetId);

    List<MediaVideoHlsPackage> findBySourceVersionIdForUpdateOrderByIdAsc(UUID sourceVersionId);

    List<MediaVideoHlsPackage> findByAssetIdForUpdateOrderByIdAsc(UUID assetId);

    List<MediaVideoHlsPackage> findByAssetId(UUID assetId);

    boolean existsByAssetId(UUID assetId);
}
