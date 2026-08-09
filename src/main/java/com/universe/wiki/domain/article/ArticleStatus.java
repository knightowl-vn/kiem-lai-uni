package com.universe.wiki.domain.article;

/**
 * Vòng đời xuất bản của một bài viết Wiki.
 */
public enum ArticleStatus {

    /**
     * Bài viết đang được soạn thảo.
     */
    DRAFT,

    /**
     * Bài viết đã được công khai.
     */
    PUBLISHED,

    /**
     * Bài viết không còn được hiển thị công khai.
     */
    ARCHIVED
}