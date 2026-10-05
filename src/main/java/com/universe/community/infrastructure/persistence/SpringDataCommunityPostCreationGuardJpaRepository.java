package com.universe.community.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CommunityPostCreationGuardJpaEntity}.
 */
@Repository
public interface SpringDataCommunityPostCreationGuardJpaRepository extends JpaRepository<CommunityPostCreationGuardJpaEntity, String> {

    /**
     * Acquires an exclusive pessimistic write lock (SELECT ... FOR UPDATE) on the author's guard row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT g FROM CommunityPostCreationGuardJpaEntity g
            WHERE g.authorUserId = :authorUserId
            """)
    Optional<CommunityPostCreationGuardJpaEntity> findByAuthorUserIdForUpdate(@Param("authorUserId") String authorUserId);
}
