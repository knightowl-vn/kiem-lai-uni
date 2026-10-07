package com.universe.screen.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenTitleJpaRepository extends JpaRepository<ScreenTitleJpaEntity, String> {

    Optional<ScreenTitleJpaEntity> findBySlug(String slug);

    Optional<ScreenTitleJpaEntity> findByIdAndStatus(String id, String status);

    Optional<ScreenTitleJpaEntity> findBySlugAndStatus(String slug, String status);

    @Query("""
            SELECT t FROM ScreenTitleJpaEntity t, ScreenFranchiseMembershipJpaEntity m
            WHERE m.titleId = t.id AND m.franchiseId = :franchiseId
            ORDER BY m.displayOrder ASC, m.id ASC
            """)
    List<ScreenTitleJpaEntity> findByFranchiseId(@Param("franchiseId") String franchiseId);

    @Query("""
            SELECT t FROM ScreenTitleJpaEntity t, ScreenFranchiseMembershipJpaEntity m
            WHERE m.titleId = t.id AND m.franchiseId = :franchiseId AND t.status = 'PUBLISHED'
            ORDER BY m.displayOrder ASC, m.id ASC
            """)
    List<ScreenTitleJpaEntity> findPublishedByFranchiseId(@Param("franchiseId") String franchiseId);

    boolean existsBySlug(String slug);
}
