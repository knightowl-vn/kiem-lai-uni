package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataMediaUploadSessionJpaRepository extends JpaRepository<MediaUploadSessionJpaEntity, String> {

    Optional<MediaUploadSessionJpaEntity> findByInitiatorPrincipalIdAndIdempotencyKey(
            String initiatorPrincipalId,
            String idempotencyKey
    );

    Optional<MediaUploadSessionJpaEntity> findByStorageAllocationId(String storageAllocationId);

    Optional<MediaUploadSessionJpaEntity> findByStorageProviderIdAndStorageKey(
            String storageProviderId,
            String storageKey
    );
}
