package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaVideoPackagingJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataMediaVideoPackagingJobJpaRepository
        extends JpaRepository<MediaVideoPackagingJobJpaEntity, String> {

    interface CandidateProjection {
        String getId();
        String getAssetId();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM MediaVideoPackagingJobJpaEntity j WHERE j.id = :id")
    Optional<MediaVideoPackagingJobJpaEntity> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT j FROM MediaVideoPackagingJobJpaEntity j
            WHERE j.sourceVersionId = :sourceVersionId
              AND j.status IN (
                  com.universe.media.domain.MediaVideoPackagingJobStatus.PENDING,
                  com.universe.media.domain.MediaVideoPackagingJobStatus.LEASED
              )
            """)
    Optional<MediaVideoPackagingJobJpaEntity> findActiveBySourceVersionIdForUpdate(
            @Param("sourceVersionId") String sourceVersionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM MediaVideoPackagingJobJpaEntity j WHERE j.assetId = :assetId ORDER BY j.id ASC")
    List<MediaVideoPackagingJobJpaEntity> findByAssetIdForUpdateOrderByIdAsc(@Param("assetId") String assetId);

    @Query("""
            SELECT j.id AS id, j.assetId AS assetId
            FROM MediaVideoPackagingJobJpaEntity j
            WHERE (j.status = com.universe.media.domain.MediaVideoPackagingJobStatus.PENDING AND j.nextAttemptAt <= :now)
               OR (j.status = com.universe.media.domain.MediaVideoPackagingJobStatus.LEASED
                   AND (j.leaseExpiresAt IS NULL OR j.leaseExpiresAt <= :now))
            ORDER BY j.nextAttemptAt ASC, j.createdAt ASC, j.id ASC
            """)
    List<CandidateProjection> findClaimableCandidates(@Param("now") Instant now, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE MediaVideoPackagingJobJpaEntity j
            SET j.heartbeatAt = :heartbeatAt,
                j.leaseExpiresAt = :leaseExpiresAt,
                j.updatedAt = :updatedAt,
                j.persistenceVersion = j.persistenceVersion + 1
            WHERE j.id = :jobId
              AND j.status = com.universe.media.domain.MediaVideoPackagingJobStatus.LEASED
              AND j.leaseOwner = :leaseOwner
              AND j.leaseGeneration = :leaseGeneration
              AND j.leaseExpiresAt IS NOT NULL
              AND j.leaseExpiresAt > :now
            """)
    int updateHeartbeatIfLeaseMatches(
            @Param("jobId") String jobId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("now") Instant now,
            @Param("heartbeatAt") Instant heartbeatAt,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("updatedAt") Instant updatedAt
    );

    boolean existsByAssetId(String assetId);
}
