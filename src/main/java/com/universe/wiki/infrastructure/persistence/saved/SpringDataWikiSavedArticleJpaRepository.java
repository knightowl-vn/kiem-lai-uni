package com.universe.wiki.infrastructure.persistence.saved;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataWikiSavedArticleJpaRepository
        extends JpaRepository<WikiSavedArticleJpaEntity, String> {

    boolean existsByUserIdAndArticleId(String userId, String articleId);

    @Modifying
    @Query("DELETE FROM WikiSavedArticleJpaEntity s WHERE s.userId = :userId AND s.articleId = :articleId")
    int deleteByUserIdAndArticleId(
            @Param("userId") String userId,
            @Param("articleId") String articleId
    );

    @Query(
            value = """
                    SELECT
                        s.id AS savedId,
                        s.article_id AS articleId,
                        s.created_at AS savedAt,
                        a.status AS articleStatus,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.title ELSE NULL END AS title,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.slug ELSE NULL END AS slug,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.article_type ELSE NULL END AS articleType,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.summary ELSE NULL END AS summary,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.cover_media_asset_id ELSE NULL END AS coverMediaAssetId,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.cover_position_x ELSE NULL END AS coverPositionX,
                        CASE WHEN a.status = 'PUBLISHED' THEN a.cover_position_y ELSE NULL END AS coverPositionY
                    FROM wiki_saved_articles s
                    LEFT JOIN wiki_articles a
                        ON a.id = s.article_id
                    WHERE s.user_id = :userId
                    ORDER BY s.created_at DESC, s.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM wiki_saved_articles s
                    WHERE s.user_id = :userId
                    """,
            nativeQuery = true
    )
    Page<WikiSavedArticleItemProjection> findSavedArticlesByUserId(
            @Param("userId") String userId,
            Pageable pageable
    );
}
