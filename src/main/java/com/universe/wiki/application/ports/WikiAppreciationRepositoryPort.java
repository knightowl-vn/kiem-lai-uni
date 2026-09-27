package com.universe.wiki.application.ports;

import com.universe.wiki.domain.appreciation.WikiAppreciationRating;

import java.util.Optional;
import java.util.UUID;

/**
 * Port repository dành cho việc lưu trữ và tra cứu bản ghi đánh giá mức độ yêu thích (Wiki Appreciation Rating).
 */
public interface WikiAppreciationRepositoryPort {

    /**
     * Tra cứu đánh giá hiện hành của một người dùng đối với một bài viết Wiki cụ thể.
     *
     * @param wikiArticleId định danh bài viết Wiki
     * @param userId định danh người dùng đã xác thực
     * @return Optional chứa WikiAppreciationRating nếu đã đánh giá, hoặc rỗng nếu chưa từng đánh giá
     */
    Optional<WikiAppreciationRating> findByWikiArticleIdAndUserId(UUID wikiArticleId, UUID userId);

    /**
     * Lưu mới hoặc cập nhật bản ghi đánh giá.
     *
     * @param rating bản ghi đánh giá cần lưu
     * @return bản ghi đánh giá đã được lưu
     */
    WikiAppreciationRating save(WikiAppreciationRating rating);
}
