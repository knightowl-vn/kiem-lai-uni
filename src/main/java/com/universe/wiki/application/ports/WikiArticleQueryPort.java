package com.universe.wiki.application.ports;

import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.contracts.dto.WikiArticlePageDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;

import java.util.Optional;
import java.util.UUID;

/**
 * Read port dành cho các truy vấn WikiArticle.
 */
public interface WikiArticleQueryPort {

    /**
     * Lấy chi tiết bài viết theo ID cho trang quản trị.
     */
    Optional<WikiArticleDTO> findDetailById(
            UUID articleId
    );

    /**
     * Lấy danh sách bài viết có phân trang và bộ lọc.
     */
    WikiArticlePageDTO findPage(
            String keyword,
            ArticleType articleType,
            ArticleStatus status,
            int page,
            int size
    );

    /**
     * Lấy bài viết công khai theo loại bài và slug.
     *
     * Chỉ trả bài có trạng thái PUBLISHED.
     */
    Optional<PublishedWikiArticleDTO>
            findPublishedByArticleTypeAndSlug(
                    ArticleType articleType,
                    Slug slug
            );
    PublishedWikiArticlePageDTO findPublishedPage(
            String keyword,
            ArticleType articleType,
            int page,
            int size
    );
}