package com.universe.community.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CommunityPostCreationEventJpaEntity}.
 */
@Repository
public interface SpringDataCommunityPostCreationEventJpaRepository extends JpaRepository<CommunityPostCreationEventJpaEntity, String> {

    /**
     * Counts the number of creations by the author strictly after the cutoff.
     */
    @Query("""
            SELECT COUNT(e) FROM CommunityPostCreationEventJpaEntity e
            WHERE e.authorUserId = :authorUserId AND e.createdAt > :cutoff
            """)
    long countCreationsAfter(
            @Param("authorUserId") String authorUserId,
            @Param("cutoff") Instant cutoff
    );

    /**
     * Finds the oldest creation timestamp for the author strictly after the cutoff.
     */
    @Query("""
            SELECT MIN(e.createdAt) FROM CommunityPostCreationEventJpaEntity e
            WHERE e.authorUserId = :authorUserId AND e.createdAt > :cutoff
            """)
    Optional<Instant> findOldestCreationTimestampAfter(
            @Param("authorUserId") String authorUserId,
            @Param("cutoff") Instant cutoff
    );

    /**
     * Finds the latest creation timestamp for the author.
     */
    @Query("""
            SELECT MAX(e.createdAt) FROM CommunityPostCreationEventJpaEntity e
            WHERE e.authorUserId = :authorUserId
            """)
    Optional<Instant> findLatestCreationTimestamp(@Param("authorUserId") String authorUserId);

    /**
     * Finds the latest matching creation timestamp for the author and caption hash strictly after the cutoff.
     */
    @Query("""
            SELECT MAX(e.createdAt) FROM CommunityPostCreationEventJpaEntity e
            WHERE e.authorUserId = :authorUserId
              AND e.normalizedCaptionHash = :normalizedCaptionHash
              AND e.createdAt > :cutoff
            """)
    Optional<Instant> findLatestMatchingCaptionCreation(
            @Param("authorUserId") String authorUserId,
            @Param("normalizedCaptionHash") String normalizedCaptionHash,
            @Param("cutoff") Instant cutoff
    );
}
