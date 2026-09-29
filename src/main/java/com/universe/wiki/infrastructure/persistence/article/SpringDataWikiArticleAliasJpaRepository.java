package com.universe.wiki.infrastructure.persistence.article;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataWikiArticleAliasJpaRepository
        extends JpaRepository<WikiArticleAliasJpaEntity, String> {

    List<WikiArticleAliasJpaEntity> findAllByArticleId(String articleId);

    List<WikiArticleAliasJpaEntity> findAllByArticleIdOrderByCreatedAtAsc(String articleId);

    List<WikiArticleAliasJpaEntity> findByNormalizedAlias(String normalizedAlias);

    List<WikiArticleAliasJpaEntity> findByNormalizedAliasOrderByCreatedAtAsc(String normalizedAlias);

    Optional<WikiArticleAliasJpaEntity> findByArticleIdAndNormalizedAlias(String articleId, String normalizedAlias);

    boolean existsByArticleIdAndNormalizedAlias(String articleId, String normalizedAlias);

    void deleteByArticleIdAndNormalizedAlias(String articleId, String normalizedAlias);

    void deleteAllByArticleId(String articleId);

    @Query("""
            SELECT a.articleId
            FROM WikiArticleAliasJpaEntity a
            JOIN WikiArticleJpaEntity article ON article.id = a.articleId
            WHERE a.normalizedAlias = :normalizedAlias
              AND article.status = 'PUBLISHED'
            ORDER BY article.updatedAt DESC, article.publishedAt DESC
            """)
    List<String> findPublishedArticleIdsByNormalizedAlias(@Param("normalizedAlias") String normalizedAlias);

    /**
     * Tra cứu ứng viên alias đã xuất bản theo alias gập Đ/đ cho tìm kiếm điều hướng.
     * Thu gọn các alias theo từng bài viết (chọn alias có thứ bậc khớp tốt nhất và tên từ điển nhỏ nhất),
     * sau đó sắp xếp theo thứ bậc khớp (Exact -> Prefix -> Contains) và tiêu chí tất định trước khi phân trang.
     */
    @Query(
            value = """
                    WITH ranked_aliases AS (
                        SELECT
                            a.article_id AS article_id,
                            a.alias AS alias,
                            CASE
                                WHEN LOWER(REPLACE(REPLACE(a.normalized_alias, 'Đ', 'd'), 'đ', 'd')) = LOWER(:foldedQuery) THEN 1
                                WHEN LOWER(REPLACE(REPLACE(a.normalized_alias, 'Đ', 'd'), 'đ', 'd')) LIKE LOWER(CONCAT(:escapedFoldedQuery, '%')) ESCAPE '\\\\' THEN 2
                                ELSE 3
                            END AS alias_rank,
                            ROW_NUMBER() OVER (
                                PARTITION BY a.article_id
                                ORDER BY
                                    CASE
                                        WHEN LOWER(REPLACE(REPLACE(a.normalized_alias, 'Đ', 'd'), 'đ', 'd')) = LOWER(:foldedQuery) THEN 1
                                        WHEN LOWER(REPLACE(REPLACE(a.normalized_alias, 'Đ', 'd'), 'đ', 'd')) LIKE LOWER(CONCAT(:escapedFoldedQuery, '%')) ESCAPE '\\\\' THEN 2
                                        ELSE 3
                                    END ASC,
                                    a.alias ASC
                            ) AS rn
                        FROM wiki_article_aliases a
                        JOIN wiki_articles art ON art.id = a.article_id
                        WHERE art.status = 'PUBLISHED'
                          AND LOWER(REPLACE(REPLACE(a.normalized_alias, 'Đ', 'd'), 'đ', 'd'))
                              LIKE LOWER(CONCAT('%', :escapedFoldedQuery, '%')) ESCAPE '\\\\'
                    )
                    SELECT
                        art.id AS articleId,
                        art.title AS title,
                        art.slug AS slug,
                        art.article_type AS articleType,
                        ra.alias AS alias,
                        art.summary AS summary,
                        art.updated_at AS updatedAt,
                        art.cover_media_asset_id AS coverMediaAssetId,
                        art.cover_position_x AS coverPositionX,
                        art.cover_position_y AS coverPositionY
                    FROM ranked_aliases ra
                    JOIN wiki_articles art ON art.id = ra.article_id
                    WHERE ra.rn = 1
                    ORDER BY
                        ra.alias_rank ASC,
                        art.article_type ASC,
                        art.id ASC
                    """,
            nativeQuery = true
    )
    List<WikiArticleAliasSearchMatchProjection> findPublishedAliasSearchCandidates(
            @Param("foldedQuery") String foldedQuery,
            @Param("escapedFoldedQuery") String escapedFoldedQuery,
            Pageable pageable
    );
}
