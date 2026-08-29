package com.universe.wiki.contracts.interfaces;

import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;

import java.util.Optional;
import java.util.UUID;

/**
 * Public Contract cho việc truy vấn bài viết Wiki từ các module khác (như Novel).
 */
public interface WikiArticleContract {

    /**
     * Tìm bài viết Wiki theo ID chỉ khi bài viết đang ở trạng thái PUBLISHED.
     *
     * @param articleId ID bài viết
     * @return Optional chứa PublishedWikiArticleDTO nếu bài viết tồn tại và đang PUBLISHED, ngược lại Optional.empty()
     */
    Optional<PublishedWikiArticleDTO> findPublishedById(UUID articleId);
}
