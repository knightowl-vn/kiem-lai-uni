package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.UploadSessionCleanupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Query("""
            SELECT s FROM MediaUploadSessionJpaEntity s
            WHERE s.status IN :sessionStatuses
              AND s.status NOT IN (
                  com.universe.media.domain.MediaUploadSessionStatus.COMPLETING,
                  com.universe.media.domain.MediaUploadSessionStatus.COMPLETED,
                  com.universe.media.domain.MediaUploadSessionStatus.CLEANED_UP
              )
              AND s.declaredContentHash IS NULL
              AND s.manifestHash IS NULL
              AND s.registeredAssetId IS NULL
              AND s.cleanupStatus IN :cleanupStatuses
              AND s.cleanupRetryCount <= :maxRetryCount
              AND (:maxLastAttemptAt IS NULL OR s.cleanupLastAttemptAt IS NULL OR s.cleanupLastAttemptAt <= :maxLastAttemptAt)
            ORDER BY s.createdAt ASC, s.id ASC
            """)
    List<MediaUploadSessionJpaEntity> findEligibleForCleanup(
            @Param("sessionStatuses") Set<MediaUploadSessionStatus> sessionStatuses,
            @Param("cleanupStatuses") Set<UploadSessionCleanupStatus> cleanupStatuses,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("maxLastAttemptAt") Instant maxLastAttemptAt,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM MediaUploadSessionJpaEntity s
            WHERE s.status IN :statuses
              AND s.expiresAt <= :now
            ORDER BY s.expiresAt ASC, s.id ASC
            """)
    List<MediaUploadSessionJpaEntity> findExpired(
            @Param("statuses") Set<MediaUploadSessionStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM MediaUploadSessionJpaEntity s
            WHERE s.status IN :statuses
              AND s.leaseExpiresAt IS NOT NULL
              AND s.leaseExpiresAt <= :now
            ORDER BY s.leaseExpiresAt ASC, s.id ASC
            """)
    List<MediaUploadSessionJpaEntity> findWithExpiredLeases(
            @Param("statuses") Set<MediaUploadSessionStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT s FROM MediaUploadSessionJpaEntity s
                    WHERE s.initiatorPrincipalId = :initiatorPrincipalId
                      AND s.consumerContext = :consumerContext
                      AND s.targetEntityType = :targetEntityType
                      AND (:mediaType IS NULL OR s.mediaType = :mediaType)
                      AND s.status = com.universe.media.domain.MediaUploadSessionStatus.COMPLETED
                      AND s.registeredAssetId IS NOT NULL
                      AND (:targetEntityId IS NULL OR s.targetEntityId = :targetEntityId)
                    ORDER BY s.completedAt DESC, s.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(s) FROM MediaUploadSessionJpaEntity s
                    WHERE s.initiatorPrincipalId = :initiatorPrincipalId
                      AND s.consumerContext = :consumerContext
                      AND s.targetEntityType = :targetEntityType
                      AND (:mediaType IS NULL OR s.mediaType = :mediaType)
                      AND s.status = com.universe.media.domain.MediaUploadSessionStatus.COMPLETED
                      AND s.registeredAssetId IS NOT NULL
                      AND (:targetEntityId IS NULL OR s.targetEntityId = :targetEntityId)
                    """
    )
    Page<MediaUploadSessionJpaEntity> findCompletedSessions(
            @Param("initiatorPrincipalId") String initiatorPrincipalId,
            @Param("consumerContext") String consumerContext,
            @Param("targetEntityType") String targetEntityType,
            @Param("mediaType") String mediaType,
            @Param("targetEntityId") String targetEntityId,
            Pageable pageable
    );
}
