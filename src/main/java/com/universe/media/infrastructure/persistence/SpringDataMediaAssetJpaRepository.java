package com.universe.media.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataMediaAssetJpaRepository
        extends JpaRepository<MediaAssetJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM MediaAssetJpaEntity a WHERE a.id = :id")
    Optional<MediaAssetJpaEntity> findByIdForUpdate(@Param("id") String id);

    @Query("SELECT a FROM MediaAssetJpaEntity a WHERE a.status = 'DELETED' AND a.updatedAt <= :cutoff ORDER BY a.updatedAt ASC")
    List<MediaAssetJpaEntity> findExpiredDeleted(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );
}
