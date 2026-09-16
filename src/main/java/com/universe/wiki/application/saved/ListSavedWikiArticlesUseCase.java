package com.universe.wiki.application.saved;

import com.universe.wiki.application.ports.WikiSavedArticlesQueryPort;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case truy vấn danh sách phân trang các bài viết Wiki đã lưu của người dùng đã xác thực.
 *
 * Kiểm tra và chuẩn hóa các ràng buộc phân trang theo quy ước hệ thống:
 * - page >= 0
 * - 1 <= size <= 100 (kích thước mặc định cho trang là 20).
 */
@Service
public class ListSavedWikiArticlesUseCase {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final WikiSavedArticlesQueryPort wikiSavedArticlesQueryPort;

    public ListSavedWikiArticlesUseCase(
            WikiSavedArticlesQueryPort wikiSavedArticlesQueryPort
    ) {
        this.wikiSavedArticlesQueryPort = Objects.requireNonNull(
                wikiSavedArticlesQueryPort,
                "WikiSavedArticlesQueryPort không được để trống."
        );
    }

    public SavedWikiArticlePageDTO execute(UUID userId, int page) {
        return execute(userId, page, DEFAULT_PAGE_SIZE);
    }

    public SavedWikiArticlePageDTO execute(UUID userId, int page, int size) {
        Objects.requireNonNull(userId, "userId không được để trống.");

        if (page < 0) {
            throw new IllegalArgumentException("Page không được nhỏ hơn 0.");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Page size phải lớn hơn hoặc bằng 1.");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size không được vượt quá " + MAX_PAGE_SIZE + "."
            );
        }

        return wikiSavedArticlesQueryPort.findSavedArticles(userId, page, size);
    }
}
