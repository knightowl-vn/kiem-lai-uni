package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * View model phân trang cho danh sách đóng góp Wiki cá nhân của người dùng.
 *
 * Chứa danh sách {@link UserWikiContributionViewItem} đã được giải quyết tính khả dụng điều hướng
 * bài viết từ bản đồ tra cứu hàng loạt (batch lookup), đồng thời giữ nguyên các thuộc tính phân trang.
 */
public record UserWikiContributionPageViewModel(
        List<UserWikiContributionViewItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public UserWikiContributionPageViewModel {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static UserWikiContributionPageViewModel from(
            UserWikiContributionPageDTO pageDto,
            Map<UUID, WikiArticleListItemDTO> liveArticlesMap,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        if (pageDto == null) {
            return new UserWikiContributionPageViewModel(List.of(), 0, 0, 0, 0, true, true);
        }

        Map<UUID, WikiArticleListItemDTO> articles = liveArticlesMap != null ? liveArticlesMap : Map.of();

        List<UserWikiContributionViewItem> viewItems = pageDto.items() == null
                ? List.of()
                : pageDto.items().stream()
                        .map(item -> UserWikiContributionViewItem.from(
                                item,
                                item.articleId() != null ? articles.get(item.articleId()) : null,
                                articleTypePathMapper
                        ))
                        .toList();

        return new UserWikiContributionPageViewModel(
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
