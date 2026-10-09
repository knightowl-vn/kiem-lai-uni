package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataStorageKeyAllocationJpaRepository
        extends JpaRepository<StorageKeyAllocationJpaEntity, String> {

    Optional<StorageKeyAllocationJpaEntity> findByStorageProviderIdAndStorageKey(
            String storageProviderId,
            String storageKey
    );

    boolean existsByStorageProviderIdAndStorageKey(
            String storageProviderId,
            String storageKey
    );
}
