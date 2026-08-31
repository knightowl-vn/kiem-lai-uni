package com.universe.novel.application.ports;

import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;

import java.util.Optional;
import java.util.UUID;

/**
 * Port do Novel module sở hữu để truy xuất thông tin bài viết Wiki đã xuất bản.
 */
public interface PublishedWikiArticlePort {

    /**
     * Tìm thông tin tóm lược của bài viết Wiki đã xuất bản theo ID.
     *
     * @param articleId ID bài viết Wiki
     * @return Optional chứa PublishedWikiArticleSummary nếu bài viết tồn tại và đang PUBLISHED, ngược lại Optional.empty()
     */
    Optional<PublishedWikiArticleSummary> findPublishedById(UUID articleId);
}
