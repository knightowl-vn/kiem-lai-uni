package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;

import java.util.List;

/**
 * View model phân trang cho danh sách bài viết Wiki đã lưu.
 *
 * Chứa danh sách {@link SavedWikiArticleViewItem} đã được giải quyết đường dẫn {@code articleTypePath},
 * đồng thời giữ nguyên các thuộc tính phân trang để tương thích hoàn toàn với Thymeleaf template.
 */
public record SavedWikiArticlePageViewModel(
        List<SavedWikiArticleViewItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public SavedWikiArticlePageViewModel {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static SavedWikiArticlePageViewModel from(
            SavedWikiArticlePageDTO pageDto,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        if (pageDto == null) {
            return new SavedWikiArticlePageViewModel(List.of(), 0, 0, 0, 0, true, true);
        }

        List<SavedWikiArticleViewItem> viewItems = pageDto.items() == null
                ? List.of()
                : pageDto.items().stream()
                        .map(item -> SavedWikiArticleViewItem.from(item, articleTypePathMapper))
                        .toList();

        return new SavedWikiArticlePageViewModel(
                viewItems,
                pageDto.page(),
                pageDto.size(),
                pageDto.totalElements(),
                pageDto.totalPages(),
                pageDto.first(),
                pageDto.last()
        );
    }
}
