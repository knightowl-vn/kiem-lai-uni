package com.universe.wiki.application.ports;

import com.universe.wiki.domain.saved.UserSavedWikiArticle;

import java.util.UUID;

/**
 * Port repository dành cho thao tác lưu trữ và quản lý bài viết Wiki đã lưu của người dùng.
 */
public interface WikiSavedArticleRepositoryPort {

    /**
     * Kiểm tra người dùng đã lưu bài viết Wiki này hay chưa.
     */
    boolean existsByUserIdAndArticleId(UUID userId, UUID articleId);

    /**
     * Lưu bản ghi bài viết đã lưu mới.
     * Ném DuplicateWikiSavedArticleException khi vi phạm ràng buộc UNIQUE(user_id, article_id).
     */
    void save(UserSavedWikiArticle savedArticle);

    /**
     * Xóa bản ghi lưu bài viết theo userId và articleId.
     * @return true nếu có bản ghi bị xóa, false nếu không tìm thấy.
     */
    boolean deleteByUserIdAndArticleId(UUID userId, UUID articleId);
}
