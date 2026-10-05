package com.universe.community.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Spring Data JPA repository for {@link CommunitySettingsJpaEntity}.
 */
@Repository
public interface SpringDataCommunitySettingsJpaRepository extends JpaRepository<CommunitySettingsJpaEntity, String> {

    /**
     * Executes atomic optimistic update of publication settings conditioned on expected version.
     *
     * @return 1 if successfully updated, 0 if expected version was stale
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CommunitySettingsJpaEntity s
            SET s.publicationMode = :mode,
                s.version = s.version + 1,
                s.updatedAt = :updatedAt,
                s.updatedByUserId = :updatedByUserId
            WHERE s.id = :id
              AND s.version = :expectedVersion
            """)
    int updateSettingsOptimistic(
            @Param("id") String id,
            @Param("mode") String mode,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedByUserId") String updatedByUserId,
            @Param("updatedAt") Instant updatedAt
    );
}
