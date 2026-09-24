package com.universe.wiki.infrastructure.persistence.orphan;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataWikiCoverOrphanJpaRepository
        extends JpaRepository<WikiCoverOrphanJpaEntity, String> {

    Optional<WikiCoverOrphanJpaEntity> findByMediaAssetId(String mediaAssetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM WikiCoverOrphanJpaEntity o WHERE o.mediaAssetId = :mediaAssetId")
    Optional<WikiCoverOrphanJpaEntity> findByMediaAssetIdForUpdate(@Param("mediaAssetId") String mediaAssetId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO wiki_cover_orphans (
                        media_asset_id,
                        status,
                        first_seen_orphan_at,
                        claim_token,
                        locked_at,
                        retry_count,
                        last_error,
                        created_at,
                        updated_at
                    ) VALUES (
                        :mediaAssetId,
                        'PENDING',
                        :observedAt,
                        NULL,
                        NULL,
                        0,
                        NULL,
                        :observedAt,
                        :observedAt
                    ) ON DUPLICATE KEY UPDATE media_asset_id = media_asset_id
                    """,
            nativeQuery = true
    )
    int insertObservationIfAbsent(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("observedAt") Instant observedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WikiCoverOrphanJpaEntity o WHERE o.mediaAssetId = :mediaAssetId")
    int deleteByMediaAssetId(@Param("mediaAssetId") String mediaAssetId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM WikiCoverOrphanJpaEntity o
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status IN (
                  com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PENDING,
                  com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PROCESSING
              )
            """)
    int deleteNonDeletingByMediaAssetId(@Param("mediaAssetId") String mediaAssetId);

    @Query("""
            SELECT o FROM WikiCoverOrphanJpaEntity o
            WHERE o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PENDING
              AND o.firstSeenOrphanAt <= :graceCutoff
            ORDER BY o.firstSeenOrphanAt ASC, o.mediaAssetId ASC
            """)
    List<WikiCoverOrphanJpaEntity> findEligiblePending(
            @Param("graceCutoff") Instant graceCutoff,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WikiCoverOrphanJpaEntity o
            SET o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PROCESSING,
                o.claimToken = :claimToken,
                o.lockedAt = :now,
                o.updatedAt = :now
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PENDING
              AND o.firstSeenOrphanAt <= :graceCutoff
            """)
    int claimIfEligible(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("graceCutoff") Instant graceCutoff,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now
    );

    @Query("""
            SELECT (COUNT(o) > 0) FROM WikiCoverOrphanJpaEntity o
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PROCESSING
              AND o.claimToken = :claimToken
            """)
    boolean isClaimOwned(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("claimToken") String claimToken
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM WikiCoverOrphanJpaEntity o
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PROCESSING
              AND o.claimToken = :claimToken
            """)
    int deleteClaimedEpoch(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("claimToken") String claimToken
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM WikiCoverOrphanJpaEntity o
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.DELETING
              AND o.claimToken = :claimToken
            """)
    int deleteClaimedDeletingEpoch(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("claimToken") String claimToken
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WikiCoverOrphanJpaEntity o
            SET o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PENDING,
                o.retryCount = o.retryCount + 1,
                o.lastError = :lastError,
                o.lockedAt = NULL,
                o.claimToken = NULL,
                o.updatedAt = :now
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PROCESSING
              AND o.claimToken = :claimToken
            """)
    int releaseClaimForRetry(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("claimToken") String claimToken,
            @Param("lastError") String lastError,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WikiCoverOrphanJpaEntity o
            SET o.claimToken = NULL,
                o.lockedAt = NULL,
                o.retryCount = o.retryCount + 1,
                o.lastError = :lastError,
                o.updatedAt = :now
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.DELETING
              AND o.claimToken = :claimToken
            """)
    int releaseDeletingClaimForRetry(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("claimToken") String claimToken,
            @Param("lastError") String lastError,
            @Param("now") Instant now
    );

    @Query("""
            SELECT o FROM WikiCoverOrphanJpaEntity o
            WHERE o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.DELETING
              AND (o.claimToken IS NULL OR o.lockedAt <= :leaseCutoff)
            ORDER BY o.updatedAt ASC, o.mediaAssetId ASC
            """)
    List<WikiCoverOrphanJpaEntity> findStaleDeletingCandidates(
            @Param("leaseCutoff") Instant leaseCutoff,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WikiCoverOrphanJpaEntity o
            SET o.claimToken = :claimToken,
                o.lockedAt = :now,
                o.updatedAt = :now
            WHERE o.mediaAssetId = :mediaAssetId
              AND o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.DELETING
              AND (o.claimToken IS NULL OR o.lockedAt <= :leaseCutoff)
            """)
    int claimDeletingForRetry(
            @Param("mediaAssetId") String mediaAssetId,
            @Param("leaseCutoff") Instant leaseCutoff,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WikiCoverOrphanJpaEntity o
            SET o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PENDING,
                o.claimToken = NULL,
                o.lockedAt = NULL,
                o.updatedAt = :now
            WHERE o.status = com.universe.wiki.domain.orphan.WikiCoverOrphanStatus.PROCESSING
              AND o.lockedAt <= :leaseCutoff
            """)
    int recoverStaleProcessing(
            @Param("leaseCutoff") Instant leaseCutoff,
            @Param("now") Instant now
    );
}
