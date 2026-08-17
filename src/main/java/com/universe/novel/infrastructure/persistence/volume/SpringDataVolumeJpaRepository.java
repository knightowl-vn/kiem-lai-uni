package com.universe.novel.infrastructure.persistence.volume;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataVolumeJpaRepository
        extends JpaRepository<VolumeJpaEntity, String> {

    Optional<VolumeJpaEntity> findBySlug(
            String slug
    );
    
    List<VolumeJpaEntity> findAllByOrderBySortOrderAsc();

    boolean existsBySlug(
            String slug
    );

    boolean existsBySortOrder(
            int sortOrder
    );
}