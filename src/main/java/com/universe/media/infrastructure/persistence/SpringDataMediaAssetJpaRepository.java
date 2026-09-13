package com.universe.media.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpringDataMediaAssetJpaRepository
        extends JpaRepository<MediaAssetJpaEntity, String> {

    @Query("SELECT a FROM MediaAssetJpaEntity a WHERE a.status = 'DELETED' AND a.updatedAt <= :cutoff ORDER BY a.updatedAt ASC")
    List<MediaAssetJpaEntity> findExpiredDeleted(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );
}
