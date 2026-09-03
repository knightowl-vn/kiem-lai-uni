package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataMediaImageVariantJpaRepository extends JpaRepository<MediaImageVariantJpaEntity, String> {

    Optional<MediaImageVariantJpaEntity> findByVersionIdAndVariantKey(
            String versionId,
            String variantKey
    );

    boolean existsByVersionIdAndVariantKey(
            String versionId,
            String variantKey
    );

    boolean existsByStorageProviderIdAndStorageKey(
            String storageProviderId,
            String storageKey
    );
}
