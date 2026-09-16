package com.universe.wiki.application.ports;

import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;

import java.util.UUID;

/**
 * Read port cho các truy vấn danh sách bài viết Wiki đã lưu của người dùng.
 */
public interface WikiSavedArticlesQueryPort {

    /**
     * Lấy danh sách bài viết đã lưu của người dùng theo phân trang với thứ tự:
     * createdAt DESC, id DESC.
     *
     * @param userId ID người dùng
     * @param page số trang (0-indexed)
     * @param size kích thước trang
     * @return danh sách phân trang SavedWikiArticlePageDTO
     */
    SavedWikiArticlePageDTO findSavedArticles(UUID userId, int page, int size);
}
