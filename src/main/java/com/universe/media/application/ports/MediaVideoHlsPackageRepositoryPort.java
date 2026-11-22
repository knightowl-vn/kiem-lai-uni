package com.universe.media.application.ports;

import com.universe.media.domain.MediaVideoHlsPackage;

import java.time.Instant;
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

    /**
     * Bounded lock-free candidate query returning packages eligible for initial purge claim
     * (enforcing conservative terminal retention floors before LIMIT) or expired PURGING reclaim.
     */
    List<MediaVideoHlsPackagePurgeCandidate> findPurgeEligibleCandidates(Instant now, int limit);

    boolean existsByAssetId(UUID assetId);
}
