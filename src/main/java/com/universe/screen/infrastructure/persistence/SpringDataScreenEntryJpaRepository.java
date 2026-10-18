package com.universe.screen.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenEntryJpaRepository extends JpaRepository<ScreenEntryJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ScreenEntryJpaEntity e WHERE e.id = :id")
    Optional<ScreenEntryJpaEntity> findByIdForUpdate(@Param("id") String id);

    Optional<ScreenEntryJpaEntity> findByIdAndStatus(String id, String status);

    Optional<ScreenEntryJpaEntity> findByTitleIdAndSlug(String titleId, String slug);

    Optional<ScreenEntryJpaEntity> findByTitleIdAndSlugAndStatus(String titleId, String slug, String status);

    @Query("SELECT e FROM ScreenEntryJpaEntity e WHERE e.titleId = :titleId ORDER BY e.id ASC")
    List<ScreenEntryJpaEntity> findByTitleId(@Param("titleId") String titleId);

    @Query("SELECT e FROM ScreenEntryJpaEntity e WHERE e.titleId = :titleId AND e.status = 'PUBLISHED' ORDER BY e.id ASC")
    List<ScreenEntryJpaEntity> findPublishedByTitleId(@Param("titleId") String titleId);

    @Query("""
            SELECT e FROM ScreenEntryJpaEntity e
            WHERE e.titleId = :titleId
              AND e.status = 'PUBLISHED'
              AND e.type = 'EPISODE'
              AND e.number > 0
            ORDER BY e.number ASC, e.id ASC
            """)
    List<ScreenEntryJpaEntity> findPublishedEpisodesByTitleId(@Param("titleId") String titleId);

    boolean existsByTitleIdAndSlug(String titleId, String slug);
}
