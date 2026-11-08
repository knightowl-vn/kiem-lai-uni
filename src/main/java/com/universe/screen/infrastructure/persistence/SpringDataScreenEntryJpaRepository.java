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

    @Query("""
            SELECT e.titleId, MAX(e.number) FROM ScreenEntryJpaEntity e
            WHERE e.titleId IN :titleIds
              AND e.status = 'PUBLISHED'
              AND e.type = 'EPISODE'
              AND e.number > 0
            GROUP BY e.titleId
            """)
    List<Object[]> findLatestPublishedEpisodeNumbersByTitleIds(@Param("titleIds") java.util.Collection<String> titleIds);

    @Query("""
            SELECT MAX(e.number) FROM ScreenEntryJpaEntity e
            WHERE e.titleId = :titleId
              AND e.status = 'PUBLISHED'
              AND e.type = 'EPISODE'
              AND e.number > 0
            """)
    Integer findLatestPublishedEpisodeNumberByTitleId(@Param("titleId") String titleId);

    boolean existsByTitleIdAndSlug(String titleId, String slug);

    @Query(value = "SELECT id FROM screen_entries WHERE video_media_asset_id = :videoMediaAssetId FOR UPDATE", nativeQuery = true)
    List<String> findEntryIdsByVideoMediaAssetIdForUpdate(@Param("videoMediaAssetId") String videoMediaAssetId);

    @Query(value = "SELECT id FROM screen_entries WHERE video_media_asset_id = :videoMediaAssetId", nativeQuery = true)
    List<String> findEntryIdsByVideoMediaAssetId(@Param("videoMediaAssetId") String videoMediaAssetId);

    @Query("SELECT e.videoMediaAssetId FROM ScreenEntryJpaEntity e WHERE e.id = :id")
    Optional<String> findVideoMediaAssetIdById(@Param("id") String id);

    @Query(value = """
            SELECT COUNT(*) FROM screen_entries e
            JOIN screen_titles t ON e.title_id = t.id
            WHERE e.video_media_asset_id = :videoMediaAssetId
              AND e.status = 'PUBLISHED'
              AND t.status = 'PUBLISHED'
              AND e.id <> :excludingEntryId
            LOCK IN SHARE MODE
            """, nativeQuery = true)
    long countOtherPublishedReferencesExcluding(
            @Param("videoMediaAssetId") String videoMediaAssetId,
            @Param("excludingEntryId") String excludingEntryId
    );

    @Query(value = """
            SELECT COUNT(*) FROM screen_entries e
            JOIN screen_titles t ON e.title_id = t.id
            WHERE e.video_media_asset_id = :videoMediaAssetId
              AND e.status = 'PUBLISHED'
              AND t.status = 'PUBLISHED'
            LOCK IN SHARE MODE
            """, nativeQuery = true)
    long countOtherPublishedReferences(
            @Param("videoMediaAssetId") String videoMediaAssetId
    );

    @Query(value = "SELECT id, video_media_asset_id FROM screen_entries WHERE title_id = :titleId AND status = 'PUBLISHED' ORDER BY id ASC LOCK IN SHARE MODE", nativeQuery = true)
    List<Object[]> findPublishedEntryVideoBindingsByTitleIdCurrentRead(@Param("titleId") String titleId);

    @Query(value = "SELECT id, video_media_asset_id FROM screen_entries WHERE title_id = :titleId ORDER BY id ASC LOCK IN SHARE MODE", nativeQuery = true)
    List<Object[]> findEntryVideoBindingsByTitleIdCurrentRead(@Param("titleId") String titleId);
}
