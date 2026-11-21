package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaVideoHlsPackageStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataMediaVideoHlsPackageJpaRepository
        extends JpaRepository<MediaVideoHlsPackageJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MediaVideoHlsPackageJpaEntity p WHERE p.id = :id")
    Optional<MediaVideoHlsPackageJpaEntity> findByIdForUpdate(@Param("id") String id);

    Optional<MediaVideoHlsPackageJpaEntity> findBySourceVersionIdAndStatus(
            String sourceVersionId,
            MediaVideoHlsPackageStatus status
    );

    @Query("""
            SELECT p FROM MediaVideoHlsPackageJpaEntity p
            JOIN MediaAssetJpaEntity a ON a.id = p.assetId
            WHERE p.assetId = :assetId
              AND a.status = 'ACTIVE'
              AND p.sourceVersionNumber = a.currentVersionNumber
              AND p.status = com.universe.media.domain.MediaVideoHlsPackageStatus.READY
            """)
    Optional<MediaVideoHlsPackageJpaEntity> findCurrentReadyByAssetId(@Param("assetId") String assetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MediaVideoHlsPackageJpaEntity p WHERE p.sourceVersionId = :sourceVersionId ORDER BY p.id ASC")
    List<MediaVideoHlsPackageJpaEntity> findBySourceVersionIdForUpdateOrderByIdAsc(
            @Param("sourceVersionId") String sourceVersionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MediaVideoHlsPackageJpaEntity p WHERE p.assetId = :assetId ORDER BY p.id ASC")
    List<MediaVideoHlsPackageJpaEntity> findByAssetIdForUpdateOrderByIdAsc(
            @Param("assetId") String assetId
    );

    List<MediaVideoHlsPackageJpaEntity> findByAssetId(String assetId);

    boolean existsByAssetId(String assetId);
}
