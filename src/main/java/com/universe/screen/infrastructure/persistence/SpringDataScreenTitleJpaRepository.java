package com.universe.screen.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenTitleJpaRepository extends JpaRepository<ScreenTitleJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ScreenTitleJpaEntity t WHERE t.id = :id")
    Optional<ScreenTitleJpaEntity> findByIdForUpdate(@Param("id") String id);

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

    @Query(
            value = """
                    SELECT t FROM ScreenTitleJpaEntity t
                    WHERE t.status = 'PUBLISHED'
                    ORDER BY t.publishedAt DESC, t.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(t) FROM ScreenTitleJpaEntity t
                    WHERE t.status = 'PUBLISHED'
                    """
    )
    Page<ScreenTitleJpaEntity> findPublishedTitles(Pageable pageable);

    boolean existsBySlug(String slug);
}
