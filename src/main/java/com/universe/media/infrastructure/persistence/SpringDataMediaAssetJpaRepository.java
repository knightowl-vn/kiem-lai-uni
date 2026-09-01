package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataMediaAssetJpaRepository
        extends JpaRepository<MediaAssetJpaEntity, String> {
}
