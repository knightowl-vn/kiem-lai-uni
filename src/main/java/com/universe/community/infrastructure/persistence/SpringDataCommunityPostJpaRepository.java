package com.universe.community.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CommunityPostJpaEntity}.
 */
@Repository
public interface SpringDataCommunityPostJpaRepository extends JpaRepository<CommunityPostJpaEntity, String> {

    /**
     * Retrieves an existing post row by ID with an exclusive pessimistic write lock (SELECT ... FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.id = :id
            """)
    Optional<CommunityPostJpaEntity> findByIdForUpdate(@Param("id") String id);
}
