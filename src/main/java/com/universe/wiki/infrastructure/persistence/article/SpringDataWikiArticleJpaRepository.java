package com.universe.wiki.infrastructure.persistence.article;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataWikiArticleJpaRepository
        extends JpaRepository<
                WikiArticleJpaEntity,
                String
        > {

    Optional<WikiArticleJpaEntity>
            findByArticleTypeAndSlug(
                    String articleType,
                    String slug
            );
    Optional<WikiArticleJpaEntity>
    findByArticleTypeAndSlugAndStatus(
            String articleType,
            String slug,
            String status
    );

    boolean existsByArticleTypeAndSlug(
            String articleType,
            String slug
    );

    Optional<WikiArticleJpaEntity> findByIdAndStatus(
            String id,
            String status
    );

    boolean existsByIdAndStatus(
            String id,
            String status
    );

    @Query(
            value = "SELECT id FROM wiki_articles WHERE cover_media_asset_id = :mediaAssetId ORDER BY id",
            nativeQuery = true
    )
    List<String> findCoverReferenceIds(@Param("mediaAssetId") String mediaAssetId);

    @Query(
            value = "SELECT id FROM wiki_articles WHERE cover_media_asset_id = :mediaAssetId LOCK IN SHARE MODE",
            nativeQuery = true
    )
    List<String> findCoverReferenceIdsCurrentRead(@Param("mediaAssetId") String mediaAssetId);

    @Query(
            value = "SELECT id FROM wiki_articles WHERE id IN (:ids) ORDER BY id FOR UPDATE",
            nativeQuery = true
    )
    List<String> lockArticleIds(@Param("ids") List<String> ids);

    /**
     * Truy vấn danh sách bài Wiki dành cho trang quản trị.
     *
     * Các bộ lọc keyword, articleType và status
     * đều có thể null.
     */
    @Query("""
            SELECT article
            FROM WikiArticleJpaEntity article
            WHERE (
                :keyword IS NULL
                OR LOWER(article.title)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(article.slug)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (
                :articleType IS NULL
                OR article.articleType = :articleType
            )
            AND (
                :status IS NULL
                OR article.status = :status
            )
            """)
    Page<WikiArticleJpaEntity> findPage(
            @Param("keyword")
            String keyword,

            @Param("articleType")
            String articleType,

            @Param("status")
            String status,

            Pageable pageable
    );
    @Query("""
            SELECT article
            FROM WikiArticleJpaEntity article
            WHERE article.status = :publishedStatus
            AND (
                :keyword IS NULL
                OR LOWER(article.title)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(article.summary)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(article.slug)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (
                :articleType IS NULL
                OR article.articleType = :articleType
            )
            """)
    Page<WikiArticleJpaEntity> findPublishedPage(
            @Param("keyword")
            String keyword,

            @Param("articleType")
            String articleType,

            @Param("publishedStatus")
            String publishedStatus,

            Pageable pageable
    );

    /**
     * Tra cứu bài viết đã xuất bản theo tiêu đề với thứ tự ưu tiên:
     * 1. Exact match (title = rawQuery)
     * 2. Prefix match (title LIKE rawQuery%)
     * 3. Contains match (title LIKE %rawQuery%)
     */
    @Query("""
            SELECT article
            FROM WikiArticleJpaEntity article
            WHERE article.status = 'PUBLISHED'
              AND LOWER(article.title) LIKE LOWER(CONCAT('%', :escapedQuery, '%')) ESCAPE '\\'
            ORDER BY
              CASE
                WHEN LOWER(article.title) = LOWER(:rawQuery) THEN 1
                WHEN LOWER(article.title) LIKE LOWER(CONCAT(:escapedQuery, '%')) ESCAPE '\\' THEN 2
                ELSE 3
              END ASC,
              article.updatedAt DESC,
              article.publishedAt DESC
            """)
    List<WikiArticleJpaEntity> findPublishedContextualMatches(
            @Param("rawQuery") String rawQuery,
            @Param("escapedQuery") String escapedQuery,
            Pageable pageable
    );

    @Query("""
            SELECT article
            FROM WikiArticleAliasJpaEntity a
            JOIN WikiArticleJpaEntity article ON article.id = a.articleId
            WHERE a.normalizedAlias = :normalizedAlias
              AND article.status = 'PUBLISHED'
            ORDER BY article.updatedAt DESC, article.publishedAt DESC
            """)
    List<WikiArticleJpaEntity> findPublishedArticlesByNormalizedAlias(
            @Param("normalizedAlias") String normalizedAlias,
            Pageable pageable
    );

    /**
     * Batch lookup of lightweight Wiki article metadata by IDs.
     */
    @Query("""
            SELECT
                article.id AS id,
                article.title AS title,
                article.slug AS slug,
                article.articleType AS articleType,
                article.status AS status,
                article.summary AS summary,
                article.updatedBy AS updatedBy,
                article.createdAt AS createdAt,
                article.updatedAt AS updatedAt,
                article.contentVersion AS contentVersion,
                article.coverMediaAssetId AS coverMediaAssetId,
                article.coverPositionX AS coverPositionX,
                article.coverPositionY AS coverPositionY
            FROM WikiArticleJpaEntity article
            WHERE article.id IN :ids
            """)
    List<WikiArticleListItemProjection> findListItemsByIds(@Param("ids") Collection<String> ids);

    /**
     * Single lookup of lightweight Wiki article eligibility metadata by ID.
     */
    @Query("""
            SELECT
                article.id AS id,
                article.articleType AS articleType,
                article.status AS status
            FROM WikiArticleJpaEntity article
            WHERE article.id = :id
            """)
    Optional<WikiArticleEligibilityProjection> findEligibilityById(@Param("id") String id);

    @Query("""
            SELECT MAX(a.coverMediaAssetId)
            FROM WikiArticleJpaEntity a
            WHERE a.coverMediaAssetId IS NOT NULL
            """)
    Optional<String> findMaxCoverMediaAssetId();

    @Query("""
            SELECT DISTINCT a.coverMediaAssetId
            FROM WikiArticleJpaEntity a
            WHERE a.coverMediaAssetId IS NOT NULL
              AND a.coverMediaAssetId <= :upperBound
            ORDER BY a.coverMediaAssetId ASC
            """)
    List<String> findDistinctCoverMediaAssetIdsFirstPage(
            @Param("upperBound") String upperBound,
            Pageable pageable
    );

    @Query("""
            SELECT DISTINCT a.coverMediaAssetId
            FROM WikiArticleJpaEntity a
            WHERE a.coverMediaAssetId IS NOT NULL
              AND a.coverMediaAssetId > :lastAssetId
              AND a.coverMediaAssetId <= :upperBound
            ORDER BY a.coverMediaAssetId ASC
            """)
    List<String> findDistinctCoverMediaAssetIdsSubsequentPage(
            @Param("lastAssetId") String lastAssetId,
            @Param("upperBound") String upperBound,
            Pageable pageable
    );

    /**
     * Tra cứu ứng viên bài viết đã xuất bản theo tiêu đề gập Đ/đ cho tìm kiếm điều hướng.
     * Sắp xếp theo thứ bậc khớp (Exact -> Prefix -> Contains) trực tiếp tại DB trước khi phân trang.
     */
    @Query("""
            SELECT
                article.id AS id,
                article.title AS title,
                article.slug AS slug,
                article.articleType AS articleType,
                article.status AS status,
                article.summary AS summary,
                article.updatedBy AS updatedBy,
                article.createdAt AS createdAt,
                article.updatedAt AS updatedAt,
                article.contentVersion AS contentVersion,
                article.coverMediaAssetId AS coverMediaAssetId,
                article.coverPositionX AS coverPositionX,
                article.coverPositionY AS coverPositionY
            FROM WikiArticleJpaEntity article
            WHERE article.status = 'PUBLISHED'
              AND LOWER(REPLACE(REPLACE(article.title, 'Đ', 'd'), 'đ', 'd'))
                  LIKE LOWER(CONCAT('%', :escapedFoldedQuery, '%')) ESCAPE '\\'
            ORDER BY
              CASE
                WHEN LOWER(REPLACE(REPLACE(article.title, 'Đ', 'd'), 'đ', 'd')) = LOWER(:foldedQuery) THEN 1
                WHEN LOWER(REPLACE(REPLACE(article.title, 'Đ', 'd'), 'đ', 'd')) LIKE LOWER(CONCAT(:escapedFoldedQuery, '%')) ESCAPE '\\' THEN 2
                ELSE 3
              END ASC,
              article.articleType ASC,
              article.id ASC
            """)
    List<WikiArticleListItemProjection> findPublishedTitleSearchCandidates(
            @Param("foldedQuery") String foldedQuery,
            @Param("escapedFoldedQuery") String escapedFoldedQuery,
            Pageable pageable
    );
}
