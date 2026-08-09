package com.universe.wiki.infrastructure.persistence.article;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}