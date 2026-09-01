package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataMediaAssetVersionJpaRepository
        extends JpaRepository<MediaAssetVersionJpaEntity, String> {

    Optional<MediaAssetVersionJpaEntity> findByAssetIdAndVersionNumber(
            String assetId,
            int versionNumber
    );

    boolean existsByStorageProviderIdAndStorageKey(
            String storageProviderId,
            String storageKey
    );

    List<MediaAssetVersionJpaEntity> findByContentHash(
            String contentHash
    );
}
